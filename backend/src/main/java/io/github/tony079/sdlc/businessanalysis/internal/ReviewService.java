package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.ReviewDetail;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.ReviewQueueEntry;
import io.github.tony079.sdlc.businessanalysis.internal.ReviewRepository.VersionRow;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffAccess.StaffContext;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.requirement.api.RequirementInputs;
import io.github.tony079.sdlc.workflow.api.Transition;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business Reviewer operations (TDD 16.7, 19.2, 19.3, 20.8): staff with MFA, an active assignment and the
 * BUSINESS_REVIEWER capability, re-checked on every call. Approval T1 is bound to the exact version, content hash and
 * input-set hash; it requires every flagged item acknowledged, unchanged inputs, and an approver who wrote no correction
 * notes for that version (four-eyes; the database enforces it too).
 */
@Service
@Profile("!migrate & !provision")
class ReviewService {

    static final int MAX_NOTES = 50;

    record Note(String itemKey, String instruction) {
    }

    private final ReviewRepository reviews;
    private final StaffAccess staffAccess;
    private final InputSets inputSets;
    private final RequirementInputs requirements;
    private final WorkflowEngine workflow;
    private final AuditWriter audit;
    private final JsonMapper json;

    ReviewService(ReviewRepository reviews, StaffAccess staffAccess, InputSets inputSets, RequirementInputs requirements,
                  WorkflowEngine workflow, AuditWriter audit, JsonMapper json) {
        this.reviews = reviews;
        this.staffAccess = staffAccess;
        this.inputSets = inputSets;
        this.requirements = requirements;
        this.workflow = workflow;
        this.audit = audit;
        this.json = json;
    }

    private StaffContext reviewer() {
        return staffAccess.requireCapability(StaffCapability.BUSINESS_REVIEWER);
    }

    @Transactional(readOnly = true)
    public List<ReviewQueueEntry> queue() {
        reviewer();
        return reviews.queue();
    }

    @Transactional
    public ReviewDetail detail(UUID versionId) {
        reviewer();
        VersionRow version = reviews.version(versionId, false).orElseThrow(ApiException::notFound);
        audit.record(AuditAction.BRD_VIEWED, "BRD_VERSION", versionId, AuditResult.SUCCESS);
        return new ReviewDetail(reviews.requirementKey(version.requirementId()),
                requirements.currentRevision(version.requirementId()).orElseThrow().content(),
                workflow.state(version.requirementId()).name(), reviews.view(versionId, true));
    }

    @Transactional
    public void acknowledge(UUID versionId, String itemKey, String note) {
        StaffContext staff = reviewer();
        VersionRow version = reviews.version(versionId, true).orElseThrow(ApiException::notFound);
        if (!"IN_REVIEW".equals(version.status())) {
            throw ApiException.conflict("VERSION_CHANGED", "This BRD version is no longer in review.");
        }
        if (!reviews.isFlagged(versionId, itemKey)) {
            throw ApiException.notFound();
        }
        reviews.acknowledge(staff.tenantId(), versionId, itemKey, staff.staffId(), note);
        audit.record(AuditAction.BRD_FLAG_ACKNOWLEDGED, "BRD_VERSION", versionId, AuditResult.SUCCESS);
    }

    @Transactional
    public WorkflowState approve(UUID versionId, String expectedContentHash) {
        StaffContext staff = reviewer();
        VersionRow version = reviews.version(versionId, false).orElseThrow(ApiException::notFound);
        WorkflowState state = workflow.lockState(version.requirementId());
        version = reviews.version(versionId, true).orElseThrow(ApiException::notFound);
        if (state != WorkflowState.INTERNAL_REVIEW || !"IN_REVIEW".equals(version.status())) {
            throw ApiException.conflict("APPROVAL_CONFLICT", "This BRD version was already decided.");
        }
        if (!version.contentHash().equals(expectedContentHash)) {
            throw ApiException.conflict("VERSION_CHANGED", "The BRD version you reviewed is not the current one.");
        }
        String current = inputSets.evaluate(version.requirementId(), version.correctionsJson())
                .map(InputSets.Draft::inputHash).orElse("NOT_READY");
        if (!current.equals(version.inputHash())) {
            throw ApiException.conflict("STALE_INPUTS", "The inputs of this BRD version have changed.");
        }
        if (reviews.openBlockingClarifications(version.requirementId()) > 0) {
            throw ApiException.conflict("OPEN_CLARIFICATIONS", "A blocking clarification is still open.");
        }
        if (reviews.unacknowledgedFlags(versionId) > 0) {
            throw ApiException.conflict("FLAGS_NOT_ACKNOWLEDGED", "Acknowledge every flagged item before approving.");
        }
        if (version.correctionsJson().contains(staff.staffId().toString())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                    "You wrote correction notes for this version, so another reviewer must approve it.");
        }
        try {
            reviews.insertApproval(staff.tenantId(), version, staff.staffId(), "APPROVED", null, null);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("APPROVAL_CONFLICT", "This BRD version was already approved.");
        }
        reviews.setVersionStatus(versionId, "APPROVED_INTERNAL");
        WorkflowState to = workflow.transition(version.requirementId(), Transition.T21_APPROVE_INTERNAL, null);
        audit.record(AuditAction.BRD_APPROVED, "BRD_VERSION", versionId, AuditResult.SUCCESS);
        return to;
    }

    @Transactional
    public WorkflowState requestRevision(UUID versionId, List<Note> notes) {
        StaffContext staff = reviewer();
        if (notes == null || notes.isEmpty() || notes.size() > MAX_NOTES || notes.stream().anyMatch(n ->
                n.instruction() == null || n.instruction().isBlank() || n.instruction().length() > 1000)) {
            throw ApiException.validation(List.of(new FieldError("notes", "INVALID")));
        }
        VersionRow version = decidable(versionId);
        for (Note note : notes) {
            if (note.itemKey() != null && !reviews.itemExists(versionId, note.itemKey())) {
                throw ApiException.validation(List.of(new FieldError("notes.itemKey", "UNKNOWN_ITEM")));
            }
        }
        List<Map<String, String>> stored = notes.stream().map(n -> {
            Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("itemKey", n.itemKey());
            m.put("instruction", n.instruction().strip());
            m.put("authorStaffId", staff.staffId().toString());
            return m;
        }).toList();
        reviews.insertApproval(staff.tenantId(), version, staff.staffId(), "REVISION_REQUESTED", null,
                json.writeValueAsString(stored));
        WorkflowState to = workflow.transition(version.requirementId(), Transition.T22_REQUEST_REVISION, null);
        audit.record(AuditAction.BRD_REVISION_REQUESTED, "BRD_VERSION", versionId, AuditResult.SUCCESS);
        return to;
    }

    @Transactional
    public WorkflowState reject(UUID versionId, String reason) {
        StaffContext staff = reviewer();
        if (reason == null || reason.strip().length() < 10 || reason.length() > 2000) {
            throw ApiException.validation(List.of(new FieldError("reason", "LENGTH")));
        }
        VersionRow version = decidable(versionId);
        reviews.insertApproval(staff.tenantId(), version, staff.staffId(), "REJECTED", reason.strip(), null);
        reviews.setVersionStatus(versionId, "WITHDRAWN");
        WorkflowState to = workflow.transition(version.requirementId(), Transition.T23_REJECT, reason.strip());
        audit.record(AuditAction.BRD_REJECTED, "BRD_VERSION", versionId, AuditResult.SUCCESS);
        return to;
    }

    private VersionRow decidable(UUID versionId) {
        VersionRow version = reviews.version(versionId, false).orElseThrow(ApiException::notFound);
        WorkflowState state = workflow.lockState(version.requirementId());
        if (state != WorkflowState.INTERNAL_REVIEW || !Set.of("IN_REVIEW").contains(version.status())) {
            throw ApiException.conflict("APPROVAL_CONFLICT", "This BRD version was already decided.");
        }
        return version;
    }
}
