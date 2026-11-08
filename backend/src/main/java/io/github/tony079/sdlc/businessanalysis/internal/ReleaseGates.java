package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.ReviewRepository.VersionRow;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Mandatory release gates (owner decision O-16). Evaluated when a Release Manager approves and again by the worker
 * when it executes the release; a failed gate cannot be bypassed (no override, no feature flag reads these gates).
 * <ol>
 *   <li>the version is internally approved and the requirement is INTERNAL_APPROVED;</li>
 *   <li>the internal approval (T1) is still VALID;</li>
 *   <li>the inputs are unchanged since the version was produced;</li>
 *   <li>no clarification is open;</li>
 *   <li>for each required scan kind ({@code sdlc.release.required-scan-kinds}, default SECRET), the latest scan of the
 *       pinned snapshot COMPLETED with no finding at or above {@code sdlc.release.blocking-severity} (default HIGH).
 *       A scan that did not run (UNAVAILABLE/FAILED/absent) fails the gate.</li>
 * </ol>
 */
@Component
@Profile("!migrate & !provision")
class ReleaseGates {

    record Gate(String name, boolean passed, String detail) {
    }

    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

    private final ReviewRepository reviews;
    private final InputSets inputSets;
    private final WorkflowEngine workflow;
    private final JdbcClient jdbc;
    private final List<String> requiredKinds;
    private final Set<String> blocking;

    ReleaseGates(ReviewRepository reviews, InputSets inputSets, WorkflowEngine workflow, JdbcClient jdbc,
                 @Value("${sdlc.release.required-scan-kinds:SECRET}") String requiredKinds,
                 @Value("${sdlc.release.blocking-severity:HIGH}") String blockingSeverity) {
        this.reviews = reviews;
        this.inputSets = inputSets;
        this.workflow = workflow;
        this.jdbc = jdbc;
        this.requiredKinds = Arrays.stream(requiredKinds.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT)).toList();
        int index = SEVERITIES.indexOf(blockingSeverity.strip().toUpperCase(Locale.ROOT));
        if (index < 0) {
            throw new IllegalStateException("sdlc.release.blocking-severity must be one of " + SEVERITIES);
        }
        this.blocking = Set.copyOf(SEVERITIES.subList(0, index + 1));
    }

    List<Gate> evaluate(VersionRow version) {
        List<Gate> gates = new ArrayList<>();
        boolean approved = "APPROVED_INTERNAL".equals(version.status())
                && workflow.state(version.requirementId()) == WorkflowState.INTERNAL_APPROVED;
        gates.add(new Gate("VERSION_INTERNALLY_APPROVED", approved, version.status()));
        gates.add(new Gate("INTERNAL_APPROVAL_VALID", reviews.validApproval(version.id()), null));
        String current = inputSets.evaluate(version.requirementId(), version.correctionsJson())
                .map(InputSets.Draft::inputHash).orElse("NOT_READY");
        gates.add(new Gate("INPUTS_UNCHANGED", current.equals(version.inputHash()), null));
        int open = jdbc.sql("""
                SELECT count(*) FROM app.clarification WHERE requirement_id = :r AND status IN ('OPEN', 'NEEDS_INFO', 'ANSWERED')
                """).param("r", version.requirementId()).query(Integer.class).single();
        gates.add(new Gate("NO_OPEN_CLARIFICATIONS", open == 0, open == 0 ? null : open + " open"));
        UUID snapshot = snapshotOf(version);
        for (String kind : requiredKinds) {
            record Run(UUID id, String status, String provider) {
            }
            Run run = jdbc.sql("""
                    SELECT id, status, provider FROM app.security_scan_run WHERE snapshot_id = :s AND kind = :k
                    ORDER BY finished_at DESC LIMIT 1
                    """).param("s", snapshot).param("k", kind)
                    .query((rs, n) -> new Run(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                    .optional().orElse(null);
            if (run == null || !"COMPLETED".equals(run.status())) {
                gates.add(new Gate("SECURITY_SCAN_" + kind, false, run == null ? "NOT_RUN" : run.status()));
                continue;
            }
            int blockingFindings = jdbc.sql("""
                    SELECT count(*) FROM app.security_finding WHERE scan_run_id = :r AND severity IN (:sev)
                    """).param("r", run.id()).param("sev", blocking).query(Integer.class).single();
            gates.add(new Gate("SECURITY_SCAN_" + kind, blockingFindings == 0,
                    run.provider() + (blockingFindings == 0 ? "" : ": " + blockingFindings + " blocking findings")));
        }
        return gates;
    }

    UUID snapshotOf(VersionRow version) {
        return jdbc.sql("SELECT snapshot_id FROM app.analysis_input_set WHERE id = :i").param("i", version.inputSetId())
                .query(UUID.class).single();
    }

    String commitOf(VersionRow version) {
        return jdbc.sql("SELECT commit_sha FROM app.analysis_input_set WHERE id = :i").param("i", version.inputSetId())
                .query(String.class).single();
    }

    static boolean allPassed(List<Gate> gates) {
        return gates.stream().allMatch(Gate::passed);
    }
}
