package io.github.tony079.sdlc.securitymonitoring.internal;

import java.time.Duration;
import java.util.List;

/**
 * C-17 alert rules (owner decision 2026-11-08). SINGLE rules alert on each event, deduplicated per type and target in
 * a 15-minute bucket; THRESHOLD rules alert when the count of a type (per actor when {@code perActor}) within a window
 * reaches the threshold, deduplicated per window bucket. Severities follow the event's risk, not the event's own
 * recorded severity.
 */
final class AlertRules {

    enum Mode { SINGLE, THRESHOLD }

    record Rule(String id, String eventType, String severity, Mode mode, int threshold, Duration window,
                boolean perActor) {
    }

    static final Duration SINGLE_BUCKET = Duration.ofMinutes(15);

    static final List<Rule> RULES = List.of(
            new Rule("TENANT_SOFT_DELETED", "TENANT_SOFT_DELETED", "CRITICAL", Mode.SINGLE, 1, SINGLE_BUCKET, false),
            new Rule("TENANT_RESTORED", "TENANT_RESTORED", "HIGH", Mode.SINGLE, 1, SINGLE_BUCKET, false),
            new Rule("TENANT_DELETION_REQUESTED", "TENANT_DELETION_REQUESTED", "HIGH", Mode.SINGLE, 1, SINGLE_BUCKET, false),
            new Rule("ADMIN_BOOTSTRAP", "ADMIN_BOOTSTRAP", "HIGH", Mode.SINGLE, 1, SINGLE_BUCKET, false),
            new Rule("PRIVILEGE_CHANGED", "PRIVILEGE_CHANGED", "HIGH", Mode.SINGLE, 1, SINGLE_BUCKET, false),
            new Rule("MALWARE_DETECTED", "MALWARE_DETECTED", "HIGH", Mode.SINGLE, 1, SINGLE_BUCKET, false),
            new Rule("GITHUB_CREDENTIAL_FAILURE", "GITHUB_CREDENTIAL_FAILURE", "HIGH", Mode.SINGLE, 1, SINGLE_BUCKET,
                    false),
            new Rule("RELEASE_GATE_FAILED", "RELEASE_GATE_FAILED", "MEDIUM", Mode.SINGLE, 1, SINGLE_BUCKET, false),
            new Rule("FEATURE_FLAG_CHANGED", "FEATURE_FLAG_CHANGED", "LOW", Mode.SINGLE, 1, SINGLE_BUCKET, false),
            new Rule("REPEATED_AUTH_FAILURES", "LOGIN_FAILED", "HIGH", Mode.THRESHOLD, 10, Duration.ofMinutes(5), false),
            new Rule("LOGIN_THROTTLED", "LOGIN_THROTTLED", "MEDIUM", Mode.SINGLE, 1, SINGLE_BUCKET, false),
            new Rule("REPEATED_MFA_FAILURES", "MFA_FAILED", "HIGH", Mode.THRESHOLD, 5, Duration.ofMinutes(5), true),
            new Rule("CROSS_TENANT_PROBING", "RESOURCE_NOT_FOUND", "HIGH", Mode.THRESHOLD, 20, Duration.ofMinutes(5), true),
            new Rule("REPEATED_STAFF_DENIALS", "STAFF_ACCESS_DENIED", "HIGH", Mode.THRESHOLD, 5, Duration.ofMinutes(10),
                    true),
            new Rule("LINK_TOKEN_PROBING", "LINK_REQUEST_REJECTED", "MEDIUM", Mode.THRESHOLD, 5, Duration.ofMinutes(10),
                    true));

    private AlertRules() {
    }
}
