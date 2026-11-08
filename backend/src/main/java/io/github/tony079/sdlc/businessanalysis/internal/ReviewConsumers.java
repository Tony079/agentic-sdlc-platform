package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.ReviewRepository.VersionRow;
import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Engine reactions of R1-M6 (worker, effectively once per event):
 * <ul>
 *   <li>ReleaseApproved (owner decision O-16: release mode MANUAL): T25, separate from the Release Manager's approval,
 *       after re-checking every mandatory gate ({@link ReleaseGates}) and that the release approval is still bound to
 *       the version's content hash. Changed inputs block the release, invalidate the approvals and mark the version
 *       STALE; any other failed gate invalidates only the release approval. BrdApprovedInternal no longer releases.</li>
 *   <li>RevisionRequested / ClientChangesRequested: T24 / T28 with a new input set that includes the reviewer's notes
 *       or the client's request.</li>
 * </ul>
 */
@Component
@Profile("!migrate & !provision")
class ReviewConsumers implements EventConsumer {

    private static final Set<String> EVENTS = Set.of(EventTypes.RELEASE_APPROVED, EventTypes.REVISION_REQUESTED,
            EventTypes.CLIENT_CHANGES_REQUESTED);

    private final ReviewRepository reviews;
    private final ClarificationRepository clarificationRepository;
    private final InputSets inputSets;
    private final AnalysisStarter starter;
    private final WorkflowEngine workflow;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final JsonMapper json;
    private final ReleaseGates gates;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    ReviewConsumers(ReviewRepository reviews, ClarificationRepository clarificationRepository, InputSets inputSets, AnalysisStarter starter, WorkflowEngine workflow,
                    OutboxPublisher outbox, AuditWriter audit, JsonMapper json, ReleaseGates gates,
                    org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.gates = gates;
        this.jdbc = jdbc;
        this.reviews = reviews;
        this.clarificationRepository = clarificationRepository;
        this.inputSets = inputSets;
        this.starter = starter;
        this.workflow = workflow;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
    }

    @Override
    public String name() {
        return "review-engine";
    }

    @Override
    public boolean accepts(String eventType) {
        return EVENTS.contains(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        UUID requirementId = event.uuid("requirementId");
        switch (event.eventType()) {
            case EventTypes.RELEASE_APPROVED -> release(requirementId, event.uuid("releaseApprovalId"));
            case EventTypes.REVISION_REQUESTED -> {
                if (workflow.lockState(requirementId) == WorkflowState.REVISION_REQUESTED) {
                    starter.start(requirementId, withClarifications(requirementId,
                            reviews.latestRevisionNotes(requirementId).orElse("[]")));
                }
            }
            case EventTypes.CLIENT_CHANGES_REQUESTED -> {
                if (workflow.lockState(requirementId) == WorkflowState.CLIENT_CHANGES_REQUESTED) {
                    String text = reviews.latestClientChange(requirementId).orElse("");
                    starter.start(requirementId, json.writeValueAsString(
                            List.of(Map.of("source", "CLIENT", "instruction", text))));
                }
            }
            default -> { }
        }
    }

    /** FR-DOC-10: clarification answers given during review feed the next version, never the approved one. */
    private String withClarifications(UUID requirementId, String notesJson) {
        java.util.List<Object> merged = new java.util.ArrayList<>();
        json.readTree(notesJson).forEach(merged::add);
        merged.addAll(clarificationRepository.answeredInputs(requirementId));
        return json.writeValueAsString(merged);
    }

    private void release(UUID requirementId, UUID releaseApprovalId) {
        if (workflow.lockState(requirementId) != WorkflowState.INTERNAL_APPROVED) {
            invalidateRelease(releaseApprovalId);
            return;
        }
        VersionRow version = reviews.latestWithStatus(requirementId, Set.of("APPROVED_INTERNAL")).orElse(null);
        String boundHash = jdbc.sql("""
                SELECT content_hash FROM app.release_approval WHERE id = :id AND status = 'APPROVED'
                """).param("id", releaseApprovalId).query(String.class).optional().orElse(null);
        if (version == null || boundHash == null || !boundHash.equals(version.contentHash())) {
            invalidateRelease(releaseApprovalId);
            return;
        }
        String current = inputSets.evaluate(requirementId, version.correctionsJson()).map(InputSets.Draft::inputHash)
                .orElse("NOT_READY");
        if (!reviews.validApproval(version.id()) || !current.equals(version.inputHash())) {
            invalidateRelease(releaseApprovalId);
            reviews.invalidateApprovals(version.id(), "InputsChanged");
            reviews.setVersionStatus(version.id(), "STALE");
            outbox.publish("REQUIREMENT", requirementId, EventTypes.APPROVAL_INVALIDATED,
                    Map.of("requirementId", requirementId.toString(), "brdVersionId", version.id().toString()));
            audit.record(AuditAction.BRD_RELEASE_BLOCKED, "BRD_VERSION", version.id(), AuditResult.DENIED);
            return;
        }
        if (!ReleaseGates.allPassed(gates.evaluate(version))) {
            invalidateRelease(releaseApprovalId);
            audit.record(AuditAction.BRD_RELEASE_BLOCKED, "BRD_VERSION", version.id(), AuditResult.DENIED);
            return;
        }
        reviews.setVersionStatus(version.id(), "RELEASED");
        workflow.release(requirementId);
        jdbc.sql("UPDATE app.release_approval SET status = 'EXECUTED', executed_at = clock_timestamp() WHERE id = :id")
                .param("id", releaseApprovalId).update();
        reviews.notify(TenantContext.requireTenant(), reviews.requirementOwner(requirementId), "BRD_RELEASED",
                requirementId);
        audit.record(AuditAction.BRD_RELEASED, "BRD_VERSION", version.id(), AuditResult.SUCCESS);
    }

    private void invalidateRelease(UUID releaseApprovalId) {
        if (jdbc.sql("UPDATE app.release_approval SET status = 'INVALIDATED' WHERE id = :id AND status = 'APPROVED'")
                .param("id", releaseApprovalId).update() > 0) {
            audit.record(AuditAction.RELEASE_APPROVAL_INVALIDATED, "RELEASE_APPROVAL", releaseApprovalId,
                    AuditResult.DENIED);
        }
    }
}
