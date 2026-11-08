package io.github.tony079.sdlc.requirement.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffAccess.StaffContext;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.requirement.api.RequirementView.Revision;
import io.github.tony079.sdlc.workflow.api.Transition;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual intake control by a Business Reviewer (C-06/C-07, owner decision 2026-11-08).
 * <ul>
 *   <li><b>Resume (T04):</b> the requirement must still be in MANUAL_INTAKE, the reviewer must name the revision they
 *       reviewed (a newer revision is refused as stale), and a Client Approver must have authorised a repository
 *       link (C-18/Q-A). A new revision converts it to EXISTING_REPOSITORY with that URL (PRD FR-REQ-11); the same
 *       workflow continues (no duplicate requirement) with repository validation.</li>
 *   <li><b>Close (T05):</b> with a reason; the requirement is CANCELLED, follow-up work stops, the owner is notified.</li>
 *   <li>A closed requirement cannot be resumed: T04 exists only from MANUAL_INTAKE, and Release 1 has no reopening
 *       transition (reopening needs its own authorised, audited transition, which is not defined).</li>
 * </ul>
 */
@Service
@Profile("!migrate & !provision & !retention")
class ManualIntakeService {

    static final int REASON_MIN = 10;
    static final int REASON_MAX = 500;

    private final RequirementRepository requirements;
    private final WorkflowEngine workflow;
    private final StaffAccess staffAccess;
    private final AuditWriter audit;

    ManualIntakeService(RequirementRepository requirements, WorkflowEngine workflow, StaffAccess staffAccess,
                        AuditWriter audit) {
        this.requirements = requirements;
        this.workflow = workflow;
        this.staffAccess = staffAccess;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<RequirementRepository.ManualIntakeEntry> queue() {
        staffAccess.requireCapability(StaffCapability.BUSINESS_REVIEWER);
        return requirements.manualIntake();
    }

    @Transactional
    public WorkflowState resume(UUID requirementId, Integer expectedRevisionNo, String reason) {
        StaffContext staff = staffAccess.requireCapability(StaffCapability.BUSINESS_REVIEWER);
        String trimmed = reason(reason);
        if (expectedRevisionNo == null) {
            throw ApiException.validation(List.of(new FieldError("expectedRevisionNo", "REQUIRED")));
        }
        RequirementRepository.Row row = requirements.find(requirementId).orElseThrow(ApiException::notFound);
        WorkflowState state = workflow.lockState(requirementId);
        if (state != WorkflowState.MANUAL_INTAKE) {
            throw ApiException.conflict("NOT_IN_MANUAL_INTAKE",
                    "Only a requirement in manual intake can be resumed (current state " + state + ").");
        }
        if (row.currentRevisionNo() != expectedRevisionNo) {
            throw ApiException.conflict("STALE_REVISION",
                    "The requirement changed since you reviewed it. Reload and review the current revision.");
        }
        String repositoryUrl = requirements.authorisedRepositoryUrl(requirementId)
                .orElseThrow(() -> ApiException.conflict("REPOSITORY_LINK_REQUIRED",
                        "A Client Approver must authorise a repository link before the requirement can resume."));
        Revision current = requirements.findRevision(requirementId, row.currentRevisionNo()).orElseThrow();
        RequirementFields base = RequirementFields.of(current.content());
        RequirementFields converted = new RequirementFields(RequirementFields.EXISTING_REPOSITORY, base.title(),
                base.description(), base.businessObjective(), base.expectedOutcomes(), base.acceptanceCriteria(),
                repositoryUrl, base.constraints(), base.integrations());
        int next = row.currentRevisionNo() + 1;
        if (!requirements.advance(requirementId, row.version(), next)) {
            throw ApiException.versionChanged();
        }
        requirements.insertRevision(staff.tenantId(), requirementId, next, converted, row.createdBy(), staff.staffId());
        WorkflowState to = workflow.resumeManualIntake(requirementId, trimmed);
        audit.record(AuditAction.MANUAL_INTAKE_RESUMED, "REQUIREMENT", requirementId, AuditResult.SUCCESS);
        return to;
    }

    @Transactional
    public WorkflowState close(UUID requirementId, String reason) {
        staffAccess.requireCapability(StaffCapability.BUSINESS_REVIEWER);
        String trimmed = reason(reason);
        RequirementRepository.Row row = requirements.find(requirementId).orElseThrow(ApiException::notFound);
        workflow.lockState(requirementId);
        if (!requirements.advance(requirementId, row.version(), row.currentRevisionNo())) {
            throw ApiException.versionChanged();
        }
        WorkflowState to = workflow.transition(requirementId, Transition.T05_CLOSE_MANUAL_INTAKE, trimmed);
        audit.record(AuditAction.MANUAL_INTAKE_CLOSED, "REQUIREMENT", requirementId, AuditResult.SUCCESS);
        return to;
    }

    private static String reason(String reason) {
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.length() < REASON_MIN || trimmed.length() > REASON_MAX) {
            throw ApiException.validation(List.of(new FieldError("reason", "INVALID_LENGTH")));
        }
        return trimmed;
    }
}
