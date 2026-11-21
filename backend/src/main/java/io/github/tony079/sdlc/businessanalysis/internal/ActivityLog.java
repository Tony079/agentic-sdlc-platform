package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GD-29: the agents' activity timeline. The worker appends one line per step as it happens; each line commits in its
 * own short transaction, so the page shows it while the model is still working and a failure to write one never
 * affects the analysis itself. Lines are trusted text written by the engine.
 */
@Component
@Profile("!migrate & !provision")
class ActivityLog {

    private static final Logger log = LoggerFactory.getLogger(ActivityLog.class);
    private static final int MAX = 600;

    enum Stage { BRD, DESIGN, ESTIMATE, PLAN, DEVELOPMENT }

    enum Kind { STEP, DONE, WARN, FAIL, DRAFT }

    record Entry(long id, String stage, UUID runId, String kind, String message, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    ActivityLog(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    void step(UUID requirementId, Stage stage, UUID runId, String message) {
        add(requirementId, stage, runId, Kind.STEP, message);
    }

    void done(UUID requirementId, Stage stage, UUID runId, String message) {
        add(requirementId, stage, runId, Kind.DONE, message);
    }

    void warn(UUID requirementId, Stage stage, UUID runId, String message) {
        add(requirementId, stage, runId, Kind.WARN, message);
    }

    void fail(UUID requirementId, Stage stage, UUID runId, String message) {
        add(requirementId, stage, runId, Kind.FAIL, message);
    }

    void add(UUID requirementId, Stage stage, UUID runId, Kind kind, String message) {
        String text = message.length() <= MAX ? message : message.substring(0, MAX - 1) + "…";
        try {
            tx.executeWithoutResult(s -> jdbc.sql("""
                    INSERT INTO app.analysis_activity (tenant_id, project_id, requirement_id, stage, run_id, kind, message)
                    SELECT :t, r.project_id, r.id, :stage, :run, :kind, :msg FROM app.requirement r WHERE r.id = :r
                    """).param("t", TenantContext.requireTenant()).param("r", requirementId)
                    .param("stage", stage.name()).param("run", runId).param("kind", kind.name()).param("msg", text)
                    .update());
        } catch (RuntimeException e) {
            log.warn("activity line not recorded: {}", e.getClass().getSimpleName());
        }
    }

    /** Most recent lines kept per stage; older runs beyond this are not shown (the latest runs always are). */
    static final int LINES_PER_STAGE = 400;

    /**
     * Lines after {@code afterId}, oldest first (API, under the reader's row-level security): the most recent
     * {@link #LINES_PER_STAGE} of each stage, so a requirement with a long history still shows its latest BRD and
     * design runs (the oldest lines were returned before, which hid every newer run once a requirement had many).
     */
    List<Entry> list(UUID requirementId, long afterId) {
        return jdbc.sql("""
                SELECT id, stage, run_id, kind, message, created_at FROM (
                  SELECT a.id, a.stage, a.run_id, a.kind, a.message, a.created_at,
                         row_number() OVER (PARTITION BY a.stage ORDER BY a.id DESC) AS recent
                  FROM app.analysis_activity a WHERE a.requirement_id = :r AND a.id > :after
                ) t WHERE recent <= :perStage ORDER BY id
                """).param("perStage", LINES_PER_STAGE).param("r", requirementId).param("after", afterId)
                .query((rs, n) -> new Entry(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class))).list();
    }

    /** "3 files", "1 file". */
    static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** A few names, then "and N more". */
    static String names(java.util.Collection<String> items, int max) {
        List<String> all = List.copyOf(items);
        if (all.isEmpty()) {
            return "none";
        }
        String shown = String.join(", ", all.subList(0, Math.min(max, all.size())));
        return all.size() > max ? shown + " and " + (all.size() - max) + " more" : shown;
    }
}
