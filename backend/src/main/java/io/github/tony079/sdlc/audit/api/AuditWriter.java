package io.github.tony079.sdlc.audit.api;

import java.util.UUID;

/**
 * Append-only tenant audit trail (PRD FR-AUD-01/05). Events carry identifiers and codes only, never business
 * content, credentials or secrets (FR-AUD-06).
 */
public interface AuditWriter {

    /**
     * Records an action in the caller's transaction, so the row exists exactly when the change it describes
     * commits. Requires an active transaction.
     */
    void record(AuditAction action, String resourceType, UUID resourceId, AuditResult result);

    /**
     * Records an event in its own transaction, so it persists even when the caller's transaction rolls back.
     * For refused or failed attempts (for example an illegal workflow transition) that must stay on record.
     */
    void recordIndependently(AuditAction action, String resourceType, UUID resourceId, AuditResult result);

    enum AuditResult { SUCCESS, DENIED, FAILED }

    enum AuditAction {
        LOGIN, LOGOUT, PROJECT_CREATED,
        REQUIREMENT_CREATED, REQUIREMENT_REVISED, REQUIREMENT_SUBMITTED, REQUIREMENT_CANCELLED,
        ILLEGAL_TRANSITION_ATTEMPTED,
        // Milestone 3: engine routing and background processing (actor SYSTEM).
        REQUIREMENT_ROUTED, OUTBOX_EVENT_DEAD, JOB_DEAD, JOBS_CANCELLED,
        // Milestone 4: attachments.
        DOCUMENT_UPLOADED, DOCUMENT_DOWNLOADED, DOCUMENT_REMOVED, DOCUMENT_SCANNED_CLEAN, DOCUMENT_REJECTED,
        // Milestone 5: processing.
        DOCUMENT_PROCESSED, DOCUMENT_NOT_ANALYSABLE,
        // R1-M4: C-18 link requests and client repository authorisation (Q-A).
        REPOSITORY_LINK_REQUESTED, REPOSITORY_LINK_REQUEST_REVOKED, REPOSITORY_LINK_ATTACHED,
        // R1-M4: registration (actor STAFF), verification, validation (engine T06/T07/T09), snapshot and inventory.
        REPOSITORY_REGISTERED, REPOSITORY_VALIDATED, REPOSITORY_ACCESS_DENIED, REPOSITORY_UNAVAILABLE,
        REPOSITORY_SHA_PINNED, REPOSITORY_VALIDATION_FAILED, SNAPSHOT_CREATED, SNAPSHOT_INDEXED, SNAPSHOT_FAILED,
        // R1-M5: analysis (engine) and BA agent runs; content-free.
        ANALYSIS_STARTED, ANALYSIS_STALE, ANALYSIS_FAILED, AGENT_EXECUTION_FINISHED, BA_RESULT_REJECTED,
        BRD_VERSION_CREATED, CITATION_REJECTED,
        // R1-M6: review, approval, release, client view.
        BRD_FLAG_ACKNOWLEDGED, BRD_APPROVED, BRD_REVISION_REQUESTED, BRD_REJECTED, BRD_RELEASED, BRD_RELEASE_BLOCKED,
        BRD_VIEWED, APPROVAL_INVALIDATED, CLIENT_CHANGES_REQUESTED,
        // FR-CLR-06, TDD 17.7 (no question text in audit); document decisions (TDD 9.4 b).
        CLARIFICATION_OPENED, CLARIFICATION_ANSWERED, CLARIFICATION_RESOLVED, CLARIFICATION_REJECTED,
        CLARIFICATION_INFO_REQUESTED, CLARIFICATION_REOPENED, CLARIFICATION_REASSIGNED, CLARIFICATION_WITHDRAWN,
        DOCUMENT_DECISION_RECORDED, ANALYSIS_CANCELLED_INPUT_CHANGE, BRD_STALE,
        // Owner decisions 2026-11-08: manual intake (C-06/C-07), closure (O-25).
        MANUAL_INTAKE_RESUMED, MANUAL_INTAKE_CLOSED, REQUIREMENT_CLOSED, BRD_WITHDRAWN_ON_CANCEL,
        // O-16 manual release; O-27 operator retry; C-09 change requests.
        RELEASE_APPROVED, RELEASE_GATE_FAILED, RELEASE_APPROVAL_INVALIDATED, ADMIN_RETRY_REQUESTED,
        ADMIN_RETRY_COMPLETED, ADMIN_RETRY_REJECTED, CHANGE_REQUEST_SUBMITTED, CHANGE_REQUEST_ASSESSED,
        CHANGE_REQUEST_REVIEWED, CHANGE_REQUEST_ACCEPTED, CHANGE_REQUEST_DECLINED, CHANGE_PRIORITY_ADJUSTED,
        // Garuda (GD-01..GD-14): projects, memberships, users, invitations, restricted requirements.
        PROJECT_UPDATED, PROJECT_ARCHIVED, PROJECT_MEMBER_ADDED, PROJECT_MEMBER_ROLE_CHANGED, PROJECT_MEMBER_REMOVED,
        USER_INVITED, USER_INVITATION_RESENT, USER_INVITATION_REVOKED, USER_INVITATION_ACCEPTED, USER_ROLE_CHANGED,
        USER_DEACTIVATED, USER_REACTIVATED, USER_MFA_ENROLLED, USER_MFA_RESET, PASSWORD_RESET_REQUESTED,
        PASSWORD_RESET_COMPLETED, PASSWORD_CHANGED, REQUIREMENT_RESTRICTION_CHANGED
    }
}
