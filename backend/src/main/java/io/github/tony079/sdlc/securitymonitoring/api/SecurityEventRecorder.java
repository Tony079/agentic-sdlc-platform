package io.github.tony079.sdlc.securitymonitoring.api;

/**
 * Security monitoring events (PRD FR-MON-01), kept separate from the audit trail. Each event is committed in
 * its own transaction, so it survives when the request that triggered it fails. Events carry references
 * and hashes only; callers must never pass content, credentials or raw personal data.
 */
public interface SecurityEventRecorder {

    void record(SecurityEventType type, Severity severity, String actorRef, String targetRef);

    enum Severity { LOW, MEDIUM, HIGH }

    enum SecurityEventType {
        /** A login attempt failed (unknown organisation, unknown user, wrong password or disabled account). */
        LOGIN_FAILED,
        /** Login refused because the throttle limit was reached. */
        LOGIN_THROTTLED,
        /**
         * A tenant-scoped resource was requested by id and not found. Under RLS the application cannot tell a
         * missing id from another tenant's id, so every such miss is recorded as possible cross-tenant probing
         * (PRD FR-AUTH-04, FR-AUTH-13).
         */
        RESOURCE_NOT_FOUND
    }
}
