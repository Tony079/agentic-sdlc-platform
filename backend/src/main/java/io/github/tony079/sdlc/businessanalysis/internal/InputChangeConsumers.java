package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.ClarificationRepository.ClarificationRow;
import io.github.tony079.sdlc.businessanalysis.internal.ReviewRepository.VersionRow;
import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Engine reactions (worker) to human input and input changes:
 * <ul>
 *   <li>ClarificationAnswered: the answer is validated and RESOLVED (FR-CLR-05); when no blocking clarification remains
 *       for a requirement waiting on them, T17 starts a new execution whose input set includes every answer
 *       (FR-CLR-09/10: the new input set is built from the current inputs, so changed inputs are never used stale).</li>
 *   <li>DocumentFailed: T10 when a document now needs a decision. DocumentDecisionRecorded: T13 if guard 9.4 holds,
 *       otherwise T12 (or nothing while other decisions are pending).</li>
 *   <li>DocumentUploaded after analysis started (AC-DOC-14, AC-EXT-41): a running analysis is cancelled (jobs fenced),
 *       its execution STALE and T14; a BRD version in review becomes STALE and its approvals invalid.</li>
 *   <li>RequirementCancelled: open clarifications are WITHDRAWN.</li>
 * </ul>
 */
@Component
@Profile("!migrate & !provision")
class InputChangeConsumers implements EventConsumer {

    private static final Set<String> EVENTS = Set.of(EventTypes.CLARIFICATION_ANSWERED, EventTypes.DOCUMENT_FAILED,
            EventTypes.DOCUMENT_DECISION_RECORDED, EventTypes.DOCUMENT_UPLOADED, EventTypes.REQUIREMENT_CANCELLED,
            EventTypes.REQUIREMENT_REJECTED);

    private final ClarificationRepository clarifications;
    private final ReviewRepository reviews;
    private final InputSets inputSets;
    private final AnalysisStarter starter;
    private final WorkflowEngine workflow;
    private final JobQueue jobs;
    private final AuditWriter audit;
    private final JsonMapper json;
    private final JdbcClient jdbc;

    InputChangeConsumers(ClarificationRepository clarifications, ReviewRepository reviews, InputSets inputSets,
                         AnalysisStarter starter, WorkflowEngine workflow, JobQueue jobs, AuditWriter audit,
                         JsonMapper json, JdbcClient jdbc) {
        this.clarifications = clarifications;
        this.reviews = reviews;
        this.inputSets = inputSets;
        this.starter = starter;
        this.workflow = workflow;
        this.jobs = jobs;
        this.audit = audit;
        this.json = json;
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "analysis-inputs";
    }

    @Override
    public boolean accepts(String eventType) {
        return EVENTS.contains(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        UUID requirementId = event.uuid("requirementId");
        switch (event.eventType()) {
            case EventTypes.CLARIFICATION_ANSWERED -> resolve(requirementId, event.uuid("clarificationId"));
            case EventTypes.DOCUMENT_FAILED -> {
                if (workflow.lockState(requirementId) == WorkflowState.AWAITING_DOCUMENTS
                        && inputSets.undecidedDocuments(requirementId)) {
                    workflow.documentDecisionNeeded(requirementId); // T10
                }
            }
            case EventTypes.DOCUMENT_DECISION_RECORDED -> {
                WorkflowState state = workflow.lockState(requirementId);
                if (state == WorkflowState.AWAITING_DOCUMENT_DECISION && !inputSets.undecidedDocuments(requirementId)) {
                    if (starter.start(requirementId, "[]").isEmpty()) { // T13 when the guard holds
                        workflow.documentDecisionsRecorded(requirementId); // otherwise T12
                    }
                } else if (state == WorkflowState.AWAITING_DOCUMENTS) {
                    starter.tryStart(requirementId);
                }
            }
            case EventTypes.DOCUMENT_UPLOADED -> documentAdded(requirementId);
            case EventTypes.REQUIREMENT_CANCELLED, EventTypes.REQUIREMENT_REJECTED -> {
                if (clarifications.withdrawOpen(requirementId) > 0) {
                    audit.record(AuditAction.CLARIFICATION_WITHDRAWN, "REQUIREMENT", requirementId, AuditResult.SUCCESS);
                }
                // C-07: a requirement closed by a Business Reviewer (T05) is reported to its owner (in-app only).
                if (EventTypes.REQUIREMENT_CANCELLED.equals(event.eventType()) && "T05_CLOSE_MANUAL_INTAKE".equals(
                        jdbc.sql("""
                                SELECT t.transition FROM app.workflow_transition t
                                JOIN app.workflow_instance i ON i.id = t.instance_id AND t.seq = i.last_seq
                                WHERE i.requirement_id = :r
                                """).param("r", requirementId).query(String.class).optional().orElse(null))) {
                    reviews.notify(TenantContext.requireTenant(), reviews.requirementOwner(requirementId),
                            "REQUIREMENT_CLOSED", requirementId);
                }
                // O-25 closure: open review work is terminated so the requirement can close. Released versions stay.
                if (reviews.withdrawOpenVersions(requirementId) > 0) {
                    audit.record(AuditAction.BRD_WITHDRAWN_ON_CANCEL, "REQUIREMENT", requirementId, AuditResult.SUCCESS);
                }
            }
            default -> { }
        }
    }

    /** Resumes (T17) a requirement whose blocking clarifications are all settled, once the guard holds. */
    void resumeIfUnblocked(UUID requirementId) {
        if (workflow.lockState(requirementId) == WorkflowState.AWAITING_CLARIFICATION
                && clarifications.openBlocking(requirementId) == 0) {
            workflow.supersedeWaitingAnalysis(requirementId);
            starter.start(requirementId, corrections(requirementId));
        }
    }

    private void resolve(UUID requirementId, UUID clarificationId) {
        WorkflowState state = workflow.lockState(requirementId);
        ClarificationRow c = clarifications.find(clarificationId, true).orElse(null);
        if (c == null) {
            return;
        }
        if ("ANSWERED".equals(c.status())) {
            clarifications.setStatus(c.id(), "RESOLVED");
            audit.record(AuditAction.CLARIFICATION_RESOLVED, "CLARIFICATION", c.id(), AuditResult.SUCCESS);
        } else if (!"REJECTED".equals(c.status())) {
            return; // NEEDS_INFO or reopened meanwhile: nothing to resume
        }
        if (state == WorkflowState.AWAITING_CLARIFICATION) {
            resumeIfUnblocked(requirementId); // T17
        }
    }

    /** Previous non-clarification corrections (reviewer notes, client request) plus every clarification outcome. */
    String corrections(UUID requirementId) {
        List<Object> merged = new ArrayList<>();
        jdbc.sql("""
                SELECT corrections::text FROM app.analysis_input_set WHERE requirement_id = :r
                ORDER BY created_at DESC LIMIT 1
                """).param("r", requirementId).query(String.class).optional().ifPresent(previous -> {
                    for (JsonNode node : json.readTree(previous)) {
                        if (!"CLARIFICATION".equals(node.path("source").asString(""))) {
                            merged.add(node);
                        }
                    }
                });
        merged.addAll(clarifications.answeredInputs(requirementId));
        return json.writeValueAsString(merged);
    }

    private void documentAdded(UUID requirementId) {
        WorkflowState state = workflow.lockState(requirementId);
        if (state == WorkflowState.ANALYSING) {
            UUID stage = jdbc.sql("""
                    SELECT s.id FROM app.stage_execution s JOIN app.workflow_instance i ON i.id = s.instance_id
                    WHERE i.requirement_id = :r AND s.stage_key = 'BA_ANALYSIS' ORDER BY s.attempt_no DESC LIMIT 1
                    """).param("r", requirementId).query(UUID.class).optional().orElse(null);
            jobs.cancelForAggregate(requirementId); // raises the lease epoch: a running BA worker cannot commit
            if (stage != null) {
                workflow.markAnalysisStale(requirementId, stage, true); // T14; T11 once the new document is ready
            }
            audit.record(AuditAction.ANALYSIS_CANCELLED_INPUT_CHANGE, "REQUIREMENT", requirementId, AuditResult.SUCCESS);
        } else if (state == WorkflowState.INTERNAL_REVIEW) {
            VersionRow version = reviews.latestWithStatus(requirementId, Set.of("IN_REVIEW")).orElse(null);
            if (version != null) {
                reviews.invalidateApprovals(version.id(), "DocumentUploaded");
                reviews.setVersionStatus(version.id(), "STALE");
                audit.record(AuditAction.BRD_STALE, "BRD_VERSION", version.id(), AuditResult.SUCCESS);
            }
        }
    }

}
