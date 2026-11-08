package io.github.tony079.sdlc.identity.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.StaffCapability;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff data access for the API login (S-02: read-only on grants and assignments; MFA columns only on staff_user).
 * The provisioning process uses its own statements under the provisioning login.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class StaffRepository {

    record StaffRecord(UUID id, String email, String displayName, String passwordHash, boolean active,
                       String mfaSecretEnc, OffsetDateTime mfaEnrolledAt, boolean enrolmentOpen, Long totpLastStep) {
    }

    private final JdbcClient jdbc;

    StaffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = """
            id, email, display_name, password_hash, status = 'ACTIVE' AS active, mfa_secret_enc, mfa_enrolled_at,
            coalesce(mfa_enrol_before > clock_timestamp(), false) AS enrolment_open, totp_last_step
            """;

    Optional<StaffRecord> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app.staff_user WHERE lower(email) = lower(:email)")
                .param("email", email).query(this::map).optional();
    }

    Optional<StaffRecord> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app.staff_user WHERE id = :id").param("id", id)
                .query(this::map).optional();
    }

    private StaffRecord map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new StaffRecord(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                rs.getString("password_hash"), rs.getBoolean("active"), rs.getString("mfa_secret_enc"),
                rs.getObject("mfa_enrolled_at", OffsetDateTime.class), rs.getBoolean("enrolment_open"),
                (Long) rs.getObject("totp_last_step"));
    }

    /** Stores a pending (not yet enrolled) secret; refused by the database outside the enrolment window. */
    boolean storePendingSecret(UUID staffId, String encrypted) {
        return jdbc.sql("""
                UPDATE app.staff_user SET mfa_secret_enc = :secret
                WHERE id = :id AND mfa_enrolled_at IS NULL AND mfa_enrol_before > clock_timestamp()
                """).param("secret", encrypted).param("id", staffId).update() == 1;
    }

    boolean confirmEnrolment(UUID staffId, long step) {
        return jdbc.sql("""
                UPDATE app.staff_user SET mfa_enrolled_at = clock_timestamp(), totp_last_step = :step
                WHERE id = :id AND mfa_enrolled_at IS NULL AND mfa_secret_enc IS NOT NULL
                  AND mfa_enrol_before > clock_timestamp()
                """).param("step", step).param("id", staffId).update() == 1;
    }

    /** Atomically consumes a TOTP step: false if this step (or a later one) was already used (replay). */
    boolean consumeStep(UUID staffId, long step) {
        return jdbc.sql("""
                UPDATE app.staff_user SET totp_last_step = :step
                WHERE id = :id AND mfa_enrolled_at IS NOT NULL AND (totp_last_step IS NULL OR totp_last_step < :step)
                """).param("step", step).param("id", staffId).update() == 1;
    }

    boolean hasCapability(UUID staffId, StaffCapability capability) {
        return jdbc.sql("""
                SELECT count(*) FROM app.staff_capability_grant
                WHERE staff_id = :staff AND capability = :capability AND revoked_at IS NULL
                """).param("staff", staffId).param("capability", capability.name()).query(Integer.class).single() > 0;
    }

    record Assignment(UUID tenantId, String tenantName) {
    }

    List<Assignment> activeAssignments(UUID staffId) {
        return jdbc.sql("""
                SELECT t.id, t.name FROM app.staff_tenant_assignment a JOIN app.tenant t ON t.id = a.assigned_tenant_id
                WHERE a.staff_id = :staff AND a.revoked_at IS NULL AND t.status = 'ACTIVE' ORDER BY t.name
                """).param("staff", staffId)
                .query((rs, n) -> new Assignment(rs.getObject(1, UUID.class), rs.getString(2))).list();
    }

    /** An unrevoked assignment to the tenant, whatever the tenant's status (lifecycle operations only). */
    boolean assignedAnyStatus(UUID staffId, UUID tenantId) {
        return jdbc.sql("""
                SELECT count(*) FROM app.staff_tenant_assignment
                WHERE staff_id = :staff AND assigned_tenant_id = :tenant AND revoked_at IS NULL
                """).param("staff", staffId).param("tenant", tenantId).query(Integer.class).single() > 0;
    }

    boolean assigned(UUID staffId, UUID tenantId) {
        return activeAssignments(staffId).stream().anyMatch(a -> a.tenantId().equals(tenantId));
    }

    List<String> capabilities(UUID staffId) {
        return jdbc.sql("""
                SELECT capability FROM app.staff_capability_grant WHERE staff_id = :staff AND revoked_at IS NULL ORDER BY 1
                """).param("staff", staffId).query(String.class).list();
    }
}
