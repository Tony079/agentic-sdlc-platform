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
        RESOURCE_NOT_FOUND,
        /** The malware scanner reported a threat in an uploaded file (PRD 6C.1 rule 3, AC-EXT-33). */
        MALWARE_DETECTED,
        /** A staff MFA code was wrong or replayed (owner decision O-11). */
        MFA_FAILED,
        /** A staff operation was refused: inactive staff, missing assignment or capability (O-06, C-01). */
        STAFF_ACCESS_DENIED,
        /** A repository link request token was unknown, expired, used, revoked or presented by a wrong caller (C-18). */
        LINK_REQUEST_REJECTED,
        /** A tenant deletion or restoration was requested, verified, approved (executed) or rejected (O-25). */
        TENANT_DELETION_REQUESTED,
        TENANT_SOFT_DELETED,
        TENANT_RESTORED,
        /** A staff capability, assignment, MFA reset or status change was made (C-17). */
        PRIVILEGE_CHANGED,
        /** Administrator bootstrap ceremony activity: opened, approved, completed, refused (O-13, C-17). */
        ADMIN_BOOTSTRAP,
        /** GitHub App credential or installation-token failure (C-17). */
        GITHUB_CREDENTIAL_FAILURE,
        /** A feature flag was changed (O-08); acknowledgement is tracked separately. */
        FEATURE_FLAG_CHANGED,
        /** A release approval was refused because a mandatory gate failed (O-16). */
        RELEASE_GATE_FAILED
    }
}
