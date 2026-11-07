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
        BRD_VIEWED, APPROVAL_INVALIDATED, CLIENT_CHANGES_REQUESTED
    }
}
