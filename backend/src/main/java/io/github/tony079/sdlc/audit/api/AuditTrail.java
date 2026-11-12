package io.github.tony079.sdlc.audit.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read side of the tenant audit trail (PRD FR-AUD-01, FR-UI audit history). Entries are content-free: an action
 * code, a resource type, a result and who acted, by category only. The caller decides who may read a requirement's
 * history; row-level security limits every read to the current tenant.
 */
public interface AuditTrail {

    /** Most recent entries returned for one requirement. */
    int MAX_ENTRIES = 500;

    /**
     * The audit history of a requirement and of the documents, BRD versions and clarifications that belong to it,
     * oldest first (at most {@link #MAX_ENTRIES}, the most recent ones).
     *
     * @param viewer the user reading the history; their own actions are marked {@code YOU}
     */
    List<AuditEntry> forRequirement(UUID requirementId, UUID viewer);

    /**
     * @param actor {@code YOU}, {@code USER} (another client user), {@code STAFF} or {@code SYSTEM}; never an id
     */
    record AuditEntry(OffsetDateTime occurredAt, String actor, String action, String resourceType, String result) {
    }
}
