package io.github.tony079.sdlc.audit.api;

import java.util.UUID;

/**
 * Append-only tenant audit trail (PRD FR-AUD-01/05). Events are written in the caller's transaction, so
 * an audit row exists exactly when the change it describes commits. Events carry identifiers and codes
 * only, never business content, credentials or secrets (FR-AUD-06).
 */
public interface AuditWriter {

    /** Records an action by the current user in the current tenant. Requires an active transaction. */
    void record(AuditAction action, String resourceType, UUID resourceId, AuditResult result);

    enum AuditResult { SUCCESS, DENIED, FAILED }

    enum AuditAction { LOGIN, LOGOUT, PROJECT_CREATED }
}
