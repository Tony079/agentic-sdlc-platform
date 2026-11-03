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
        ILLEGAL_TRANSITION_ATTEMPTED
    }
}
