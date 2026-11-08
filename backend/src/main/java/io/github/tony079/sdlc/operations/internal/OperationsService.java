package io.github.tony079.sdlc.operations.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature flags and operational visibility (owner decision O-08, 2026-11-08). Platform-level: no tenant data.
 * <ul>
 *   <li>Only registered flags exist; protected flags (security and release-gate controls) cannot be changed here at
 *       all (also a database trigger). No release gate or authorisation check reads a flag.</li>
 *   <li>A change records name, environment, previous and new value, reason, actor and time. It is made by an operator
 *       holding FEATURE_FLAG_ACK and acknowledged by a <em>different</em> FEATURE_FLAG_ACK holder.</li>
 *   <li>The operations overview lists unacknowledged changes and alert-delivery state.</li>
 * </ul>
 */
@Service
@Profile("!migrate & !provision & !retention")
class OperationsService {

    record Flag(String name, String environment, String value, boolean protectedFlag, String description,
                OffsetDateTime updatedAt) {
    }

    record FlagChange(UUID id, String name, String environment, String previousValue, String newValue, String reason,
                      UUID changedBy, OffsetDateTime changedAt, UUID acknowledgedBy, OffsetDateTime acknowledgedAt) {
    }

    private final JdbcClient jdbc;
    private final StaffAccess staffAccess;
    private final SecurityEventRecorder securityEvents;

    OperationsService(JdbcClient jdbc, StaffAccess staffAccess, SecurityEventRecorder securityEvents) {
        this.jdbc = jdbc;
        this.staffAccess = staffAccess;
        this.securityEvents = securityEvents;
    }

    @Transactional(readOnly = true)
    public List<Flag> flags() {
        staffAccess.requirePlatformCapability(StaffCapability.FEATURE_FLAG_ACK);
        return jdbc.sql("""
                SELECT name, environment, value, protected, description, updated_at FROM app.feature_flag ORDER BY 1, 2
                """).query((rs, n) -> new Flag(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                rs.getString(5), rs.getObject(6, OffsetDateTime.class))).list();
    }

    @Transactional
    public FlagChange change(String name, String environment, String value, String reason) {
        UUID staff = staffAccess.requirePlatformCapability(StaffCapability.FEATURE_FLAG_ACK);
        String text = reason == null ? "" : reason.strip();
        if (text.length() < 10 || text.length() > 1000) {
            throw ApiException.validation(List.of(new FieldError("reason", "INVALID_LENGTH")));
        }
        if (value == null || value.isBlank() || value.length() > 200) {
            throw ApiException.validation(List.of(new FieldError("value", "INVALID")));
        }
        record Current(String value, boolean protectedFlag) {
        }
        Current current = jdbc.sql("""
                SELECT value, protected FROM app.feature_flag WHERE name = :n AND environment = :e FOR UPDATE
                """).param("n", name).param("e", environment)
                .query((rs, n) -> new Current(rs.getString(1), rs.getBoolean(2))).optional()
                .orElseThrow(ApiException::notFound);
        if (current.protectedFlag()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROTECTED_FLAG",
                    "This flag guards a security or release control and cannot be changed here.");
        }
        if (current.value().equals(value.strip())) {
            throw ApiException.conflict("NO_CHANGE", "The flag already has this value.");
        }
        jdbc.sql("UPDATE app.feature_flag SET value = :v, updated_at = clock_timestamp() WHERE name = :n AND environment = :e")
                .param("v", value.strip()).param("n", name).param("e", environment).update();
        UUID id = jdbc.sql("""
                INSERT INTO app.feature_flag_change (name, environment, previous_value, new_value, reason, changed_by)
                VALUES (:n, :e, :prev, :new, :reason, :staff) RETURNING id
                """).param("n", name).param("e", environment).param("prev", current.value()).param("new", value.strip())
                .param("reason", text).param("staff", staff).query(UUID.class).single();
        platformAudit(staff, "FEATURE_FLAG_CHANGED", id);
        securityEvents.record(SecurityEventType.FEATURE_FLAG_CHANGED, Severity.LOW, staff.toString(),
                name + "@" + environment);
        return change(id);
    }

    @Transactional
    public FlagChange acknowledge(UUID changeId) {
        UUID staff = staffAccess.requirePlatformCapability(StaffCapability.FEATURE_FLAG_ACK);
        FlagChange change = change(changeId);
        if (change.acknowledgedBy() != null) {
            throw ApiException.conflict("ALREADY_ACKNOWLEDGED", "This change is already acknowledged.");
        }
        if (staff.equals(change.changedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_ACKNOWLEDGEMENT_FORBIDDEN",
                    "Another operator must acknowledge your change.");
        }
        jdbc.sql("""
                UPDATE app.feature_flag_change SET acknowledged_by = :s, acknowledged_at = clock_timestamp() WHERE id = :id
                """).param("s", staff).param("id", changeId).update();
        platformAudit(staff, "FEATURE_FLAG_ACKNOWLEDGED", changeId);
        return change(changeId);
    }

    /** Operations overview: unacknowledged flag changes and security-alert delivery state. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        staffAccess.requirePlatformCapability(StaffCapability.FEATURE_FLAG_ACK);
        List<FlagChange> unacknowledged = jdbc.sql(CHANGE_SELECT + " WHERE acknowledged_by IS NULL ORDER BY changed_at")
                .query(OperationsService::changeRow).list();
        Map<String, Object> alerts = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending, count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE status = 'SENT') AS sent FROM app.security_alert_delivery
                """).query().singleRow();
        return Map.of("unacknowledgedFlagChanges", unacknowledged, "alertDeliveries", alerts);
    }

    private static final String CHANGE_SELECT = """
            SELECT id, name, environment, previous_value, new_value, reason, changed_by, changed_at, acknowledged_by,
                   acknowledged_at FROM app.feature_flag_change
            """;

    private FlagChange change(UUID id) {
        return jdbc.sql(CHANGE_SELECT + " WHERE id = :id").param("id", id).query(OperationsService::changeRow).optional()
                .orElseThrow(ApiException::notFound);
    }

    private static FlagChange changeRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new FlagChange(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getObject(8, OffsetDateTime.class),
                rs.getObject(9, UUID.class), rs.getObject(10, OffsetDateTime.class));
    }

    private void platformAudit(UUID staff, String action, UUID ref) {
        jdbc.sql("""
                INSERT INTO app.platform_audit_event (actor_staff_id, action, target_type, target_ref, result)
                VALUES (:s, :a, 'FEATURE_FLAG_CHANGE', :r, 'SUCCESS')
                """).param("s", staff).param("a", action).param("r", ref.toString()).update();
    }
}
