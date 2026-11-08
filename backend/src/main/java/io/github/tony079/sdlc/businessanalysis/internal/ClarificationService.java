package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.ClarificationRepository.ClarificationRow;
import io.github.tony079.sdlc.businessanalysis.internal.ClarificationRepository.Message;
import io.github.tony079.sdlc.businessanalysis.internal.ClarificationRepository.PendingDecision;
import io.github.tony079.sdlc.businessanalysis.internal.ClarificationRepository.VersionInfo;
import io.github.tony079.sdlc.businessanalysis.internal.ReviewRepository.VersionRow;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffAccess.StaffContext;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.requirement.api.RequirementDirectory;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clarification lifecycle (PRD FR-CLR, TDD 17) and document decisions (TDD 9.4 b) for humans. Questions are assigned
 * to a role: REQUIREMENT_OWNER (the client user who owns the requirement; C-18 owner-only visibility) or
 * BUSINESS_REVIEWER (internal analyst). Only an authenticated member of the assigned role answers (FR-CLR-05, 14);
 * the engine (worker) validates the answer and resumes the dependent work. No model ever answers.
 */
@Service
@Profile("!migrate & !provision")
class ClarificationService {

    static final Set<String> OPEN = Set.of("OPEN", "NEEDS_INFO");

    record ClarificationView(UUID id, UUID requirementId, String source, boolean blocking, String assignedRole,
                             String status, String question, String whyItMatters, String interpretations,
                             String recommendedOption, int revisionNo, String commitSha, UUID stageExecutionId,
                             UUID brdVersionId, java.time.OffsetDateTime createdAt, List<Message> messages) {
    }

    private final ClarificationRepository clarifications;
    private final ReviewRepository reviews;
    private final StaffAccess staffAccess;
    private final RequirementDirectory requirements;
    private final WorkflowEngine workflow;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final SecurityEventRecorder securityEvents;
    private final JsonMapper json;

    ClarificationService(ClarificationRepository clarifications, ReviewRepository reviews, StaffAccess staffAccess,
                         RequirementDirectory requirements, WorkflowEngine workflow, OutboxPublisher outbox,
                         AuditWriter audit, SecurityEventRecorder securityEvents, JsonMapper json) {
        this.clarifications = clarifications;
        this.reviews = reviews;
        this.staffAccess = staffAccess;
        this.requirements = requirements;
        this.workflow = workflow;
        this.outbox = outbox;
        this.audit = audit;
        this.securityEvents = securityEvents;
        this.json = json;
    }

    private ClarificationView view(ClarificationRow c, boolean withMessages) {
        return new ClarificationView(c.id(), c.requirementId(), c.source(), c.blocking(), c.assignedRole(), c.status(),
                c.question(), c.whyItMatters(), c.interpretationsJson(), c.recommendedOption(), c.revisionNo(),
                c.commitSha(), c.stageExecutionId(), c.brdVersionId(), c.createdAt(),
                withMessages ? clarifications.messages(c.id()) : List.of());
    }

    // --- client (requirement owner) -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ClarificationView> ownerInbox() {
        return clarifications.ownerInbox(TenantContext.currentActor().orElseThrow()).stream()
                .map(c -> view(c, false)).toList();
    }

    @Transactional(readOnly = true)
    public ClarificationView ownerDetail(UUID id) {
        return view(ownerVisible(id, false), true);
    }

    @Transactional
    public String ownerAnswer(UUID id, String text) {
        ClarificationRow c = ownerVisible(id, true);
        return answer(c, text, "USER", TenantContext.currentActor().orElseThrow());
    }

    @Transactional
    public String ownerReject(UUID id, String reason) {
        ClarificationRow c = ownerVisible(id, true);
        return reject(c, reason, "USER", TenantContext.currentActor().orElseThrow());
    }

    private ClarificationRow ownerVisible(UUID id, boolean lock) {
        ClarificationRow c = clarifications.find(id, lock).orElse(null);
        if (c == null || !"REQUIREMENT_OWNER".equals(c.assignedRole()) || requirements.findOwned(c.requirementId()).isEmpty()) {
            securityEvents.record(SecurityEventType.RESOURCE_NOT_FOUND, Severity.LOW,
                    TenantContext.currentActor().map(UUID::toString).orElse(null), "CLARIFICATION:" + id);
            throw ApiException.notFound();
        }
        return c;
    }

    // --- staff (Business Reviewer) ------------------------------------------------------------------------------

    private StaffContext reviewer() {
        return staffAccess.requireCapability(StaffCapability.BUSINESS_REVIEWER);
    }

    @Transactional(readOnly = true)
    public List<ClarificationView> staffInbox() {
        reviewer();
        return clarifications.tenantInbox().stream().map(c -> view(c, false)).toList();
    }

    @Transactional(readOnly = true)
    public ClarificationView staffDetail(UUID id) {
        reviewer();
        return view(clarifications.find(id, false).orElseThrow(ApiException::notFound), true);
    }

    @Transactional
    public String staffAnswer(UUID id, String text) {
        StaffContext staff = reviewer();
        ClarificationRow c = clarifications.find(id, true).orElseThrow(ApiException::notFound);
        if (!"BUSINESS_REVIEWER".equals(c.assignedRole())) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "NOT_ASSIGNED_ROLE",
                    "This question is assigned to the requirement owner.");
        }
        return answer(c, text, "STAFF", staff.staffId());
    }

    @Transactional
    public String staffReject(UUID id, String reason) {
        StaffContext staff = reviewer();
        ClarificationRow c = clarifications.find(id, true).orElseThrow(ApiException::notFound);
        if (!"BUSINESS_REVIEWER".equals(c.assignedRole())) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "NOT_ASSIGNED_ROLE",
                    "This question is assigned to the requirement owner.");
        }
        return reject(c, reason, "STAFF", staff.staffId());
    }

    /** TDD 17.2: ANSWERED to NEEDS_INFO (answer incomplete), before the engine resolved it. */
    @Transactional
    public String requestInfo(UUID id, String reason) {
        StaffContext staff = reviewer();
        ClarificationRow c = clarifications.find(id, true).orElseThrow(ApiException::notFound);
        requireText(reason, "reason", 3, 2000);
        if (!"ANSWERED".equals(c.status())) {
            throw ApiException.conflict("CLARIFICATION_STATE", "Information can be requested only on an answer not yet resolved.");
        }
        clarifications.setStatus(c.id(), "NEEDS_INFO");
        clarifications.message(staff.tenantId(), c.id(), "INFO_REQUEST", "STAFF", staff.staffId(), reason.strip());
        notifyOwnerIfAssigned(staff.tenantId(), c);
        audit.record(AuditAction.CLARIFICATION_INFO_REQUESTED, "CLARIFICATION", c.id(), AuditResult.SUCCESS);
        return "NEEDS_INFO";
    }

    /** TDD 17.2: RESOLVED/REJECTED to OPEN while the dependent work has not continued yet. */
    @Transactional
    public String reopen(UUID id, String reason) {
        StaffContext staff = reviewer();
        ClarificationRow c = clarifications.find(id, true).orElseThrow(ApiException::notFound);
        requireText(reason, "reason", 3, 2000);
        WorkflowState state = workflow.lockState(c.requirementId());
        if (!Set.of("RESOLVED", "REJECTED").contains(c.status())
                || !Set.of(WorkflowState.AWAITING_CLARIFICATION, WorkflowState.INTERNAL_REVIEW).contains(state)) {
            throw ApiException.conflict("CLARIFICATION_STATE", "This clarification cannot be reopened now.");
        }
        clarifications.setStatus(c.id(), "OPEN");
        clarifications.message(staff.tenantId(), c.id(), "REOPEN", "STAFF", staff.staffId(), reason.strip());
        notifyOwnerIfAssigned(staff.tenantId(), c);
        audit.record(AuditAction.CLARIFICATION_REOPENED, "CLARIFICATION", c.id(), AuditResult.SUCCESS);
        return "OPEN";
    }

    /** TDD 17.2: role change (person-to-person reassignment is O-24, not in R1). */
    @Transactional
    public String reassign(UUID id, String role, String reason) {
        StaffContext staff = reviewer();
        ClarificationRow c = clarifications.find(id, true).orElseThrow(ApiException::notFound);
        requireText(reason, "reason", 3, 2000);
        if (!Set.of("REQUIREMENT_OWNER", "BUSINESS_REVIEWER").contains(role) || role.equals(c.assignedRole())) {
            throw ApiException.validation(List.of(new FieldError("role", "INVALID")));
        }
        if (!OPEN.contains(c.status())) {
            throw ApiException.conflict("CLARIFICATION_STATE", "Only an open clarification can be reassigned.");
        }
        clarifications.setRole(c.id(), role);
        clarifications.message(staff.tenantId(), c.id(), "REASSIGN", "STAFF", staff.staffId(), reason.strip());
        if ("REQUIREMENT_OWNER".equals(role)) {
            reviews.notify(staff.tenantId(), reviews.requirementOwner(c.requirementId()), "CLARIFICATION_ASSIGNED",
                    c.requirementId());
        }
        audit.record(AuditAction.CLARIFICATION_REASSIGNED, "CLARIFICATION", c.id(), AuditResult.SUCCESS);
        return c.status();
    }

    /** FR-CLR-13: during review, a reviewer raises a clarification instead of approving (blocks approval). */
    @Transactional
    public UUID raise(UUID versionId, String question, String whyItMatters, String role, boolean blocking) {
        StaffContext staff = reviewer();
        requireText(question, "question", 5, 2000);
        if (!Set.of("REQUIREMENT_OWNER", "BUSINESS_REVIEWER").contains(role)) {
            throw ApiException.validation(List.of(new FieldError("assignedRole", "INVALID")));
        }
        VersionRow version = reviews.version(versionId, false).orElseThrow(ApiException::notFound);
        if (workflow.lockState(version.requirementId()) != WorkflowState.INTERNAL_REVIEW
                || !"IN_REVIEW".equals(version.status())) {
            throw ApiException.conflict("VERSION_CHANGED", "This BRD version is no longer in review.");
        }
        var set = reviews.inputSetOf(versionId);
        UUID id = clarifications.insert(staff.tenantId(), version.requirementId(), "REVIEWER", null, versionId,
                set.revisionNo(), set.commitSha(), version.inputHash(), blocking, role, question.strip(),
                whyItMatters == null ? null : whyItMatters.strip(), "[]", null);
        if ("REQUIREMENT_OWNER".equals(role)) {
            reviews.notify(staff.tenantId(), reviews.requirementOwner(version.requirementId()), "CLARIFICATION_ASSIGNED",
                    version.requirementId());
        }
        outbox.publish("REQUIREMENT", version.requirementId(), EventTypes.CLARIFICATION_OPENED,
                Map.of("requirementId", version.requirementId().toString(), "clarificationId", id.toString()));
        audit.record(AuditAction.CLARIFICATION_OPENED, "CLARIFICATION", id, AuditResult.SUCCESS);
        return id;
    }

    // --- shared -------------------------------------------------------------------------------------------------

    private String answer(ClarificationRow c, String text, String authorType, UUID author) {
        requireText(text, "text", 1, 8000);
        if (!OPEN.contains(c.status())) {
            throw ApiException.conflict("CLARIFICATION_STATE", "This clarification is not waiting for an answer.");
        }
        clarifications.setStatus(c.id(), "ANSWERED");
        clarifications.message(TenantContext.requireTenant(), c.id(), "ANSWER", authorType, author, text.strip());
        outbox.publish("REQUIREMENT", c.requirementId(), EventTypes.CLARIFICATION_ANSWERED,
                Map.of("requirementId", c.requirementId().toString(), "clarificationId", c.id().toString()));
        audit.record(AuditAction.CLARIFICATION_ANSWERED, "CLARIFICATION", c.id(), AuditResult.SUCCESS);
        return "ANSWERED";
    }

    private String reject(ClarificationRow c, String reason, String authorType, UUID author) {
        requireText(reason, "reason", 3, 2000);
        if (!OPEN.contains(c.status())) {
            throw ApiException.conflict("CLARIFICATION_STATE", "This clarification is not waiting for an answer.");
        }
        clarifications.setStatus(c.id(), "REJECTED");
        clarifications.message(TenantContext.requireTenant(), c.id(), "REJECTION", authorType, author, reason.strip());
        outbox.publish("REQUIREMENT", c.requirementId(), EventTypes.CLARIFICATION_ANSWERED,
                Map.of("requirementId", c.requirementId().toString(), "clarificationId", c.id().toString()));
        audit.record(AuditAction.CLARIFICATION_REJECTED, "CLARIFICATION", c.id(), AuditResult.SUCCESS);
        return "REJECTED";
    }

    private void notifyOwnerIfAssigned(UUID tenant, ClarificationRow c) {
        if ("REQUIREMENT_OWNER".equals(c.assignedRole())) {
            reviews.notify(tenant, reviews.requirementOwner(c.requirementId()), "CLARIFICATION_ASSIGNED", c.requirementId());
        }
    }

    // --- document decisions (TDD 9.4 b; AC-EXT-23, 31, 32) ------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PendingDecision> pendingDecisions() {
        reviewer();
        return clarifications.pendingDecisions();
    }

    /** Business Reviewer authorises excluding the exact document version from analysis (listed on the BRD). */
    @Transactional
    public void exclude(UUID versionId, String reason) {
        StaffContext staff = reviewer();
        requireText(reason, "reason", 3, 1000);
        VersionInfo v = clarifications.versionInfo(versionId).orElseThrow(ApiException::notFound);
        if (!v.current() || !InputSets.NEEDS_DECISION.contains(v.state())) {
            throw ApiException.conflict("DECISION_NOT_NEEDED", "This document version does not need a decision.");
        }
        record(staff.tenantId(), v, versionId, "EXCLUDE", "STAFF", staff.staffId(), reason);
    }

    /** The requirement owner acknowledges excluding a failed upload (AC-EXT-32). */
    @Transactional
    public void acknowledgeExclusion(UUID requirementId, UUID documentId, String reason) {
        requireText(reason, "reason", 3, 1000);
        if (requirements.findOwned(requirementId).isEmpty()) {
            throw ApiException.notFound();
        }
        UUID versionId = clarifications.currentVersionOf(documentId).orElseThrow(ApiException::notFound);
        VersionInfo v = clarifications.versionInfo(versionId).orElseThrow(ApiException::notFound);
        if (!v.requirementId().equals(requirementId)) {
            throw ApiException.notFound();
        }
        if (!"UPLOAD_FAILED".equals(v.state())) {
            throw ApiException.conflict("DECISION_NOT_ALLOWED",
                    "Only a failed upload can be excluded by the client; other documents need a Business Reviewer.");
        }
        record(TenantContext.requireTenant(), v, versionId, "ACKNOWLEDGE_EXCLUSION", "USER",
                TenantContext.currentActor().orElseThrow(), reason);
    }

    private void record(UUID tenant, VersionInfo v, UUID versionId, String decision, String actorType, UUID actor,
                        String reason) {
        if (!clarifications.recordDecision(tenant, v.requirementId(), versionId, v.state(), decision, actorType, actor,
                reason.strip())) {
            throw ApiException.conflict("DECISION_EXISTS", "A decision for this document version already exists.");
        }
        outbox.publish("REQUIREMENT", v.requirementId(), EventTypes.DOCUMENT_DECISION_RECORDED,
                Map.of("requirementId", v.requirementId().toString(), "versionId", versionId.toString()));
        audit.record(AuditAction.DOCUMENT_DECISION_RECORDED, "DOCUMENT", v.documentId(), AuditResult.SUCCESS);
    }

    private static void requireText(String value, String field, int min, int max) {
        if (value == null || value.strip().length() < min || value.length() > max) {
            throw ApiException.validation(List.of(new FieldError(field, "LENGTH")));
        }
    }

    String toJson(Object value) {
        return json.writeValueAsString(value);
    }
}
