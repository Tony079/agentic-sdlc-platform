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
 *   <li>BrdApprovedInternal: release T25 in a separate transaction from approval T1 (release mode AUTOMATIC, O-16
 *       provisional), after re-checking that the approved version is still current, the approval still VALID and the
 *       inputs unchanged; otherwise the release is blocked, the approval invalidated and the version marked STALE.</li>
 *   <li>RevisionRequested / ClientChangesRequested: T24 / T28 with a new input set that includes the reviewer's notes
 *       or the client's request.</li>
 * </ul>
 */
@Component
@Profile("!migrate & !provision")
class ReviewConsumers implements EventConsumer {

    private static final Set<String> EVENTS = Set.of(EventTypes.BRD_APPROVED_INTERNAL, EventTypes.REVISION_REQUESTED,
            EventTypes.CLIENT_CHANGES_REQUESTED);

    private final ReviewRepository reviews;
    private final ClarificationRepository clarificationRepository;
    private final InputSets inputSets;
    private final AnalysisStarter starter;
    private final WorkflowEngine workflow;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final JsonMapper json;

    ReviewConsumers(ReviewRepository reviews, ClarificationRepository clarificationRepository, InputSets inputSets, AnalysisStarter starter, WorkflowEngine workflow,
                    OutboxPublisher outbox, AuditWriter audit, JsonMapper json) {
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
            case EventTypes.BRD_APPROVED_INTERNAL -> release(requirementId);
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

    private void release(UUID requirementId) {
        if (workflow.lockState(requirementId) != WorkflowState.INTERNAL_APPROVED) {
            return;
        }
        VersionRow version = reviews.latestWithStatus(requirementId, Set.of("APPROVED_INTERNAL")).orElse(null);
        if (version == null) {
            return;
        }
        String current = inputSets.evaluate(requirementId, version.correctionsJson()).map(InputSets.Draft::inputHash)
                .orElse("NOT_READY");
        if (!reviews.validApproval(version.id()) || !current.equals(version.inputHash())) {
            reviews.invalidateApprovals(version.id(), "InputsChanged");
            reviews.setVersionStatus(version.id(), "STALE");
            outbox.publish("REQUIREMENT", requirementId, EventTypes.APPROVAL_INVALIDATED,
                    Map.of("requirementId", requirementId.toString(), "brdVersionId", version.id().toString()));
            audit.record(AuditAction.BRD_RELEASE_BLOCKED, "BRD_VERSION", version.id(), AuditResult.DENIED);
            return;
        }
        reviews.setVersionStatus(version.id(), "RELEASED");
        workflow.release(requirementId);
        reviews.notify(TenantContext.requireTenant(), reviews.requirementOwner(requirementId), "BRD_RELEASED",
                requirementId);
        audit.record(AuditAction.BRD_RELEASED, "BRD_VERSION", version.id(), AuditResult.SUCCESS);
    }
}
