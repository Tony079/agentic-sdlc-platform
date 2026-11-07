package io.github.tony079.sdlc.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.StaffCapability;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The privileged staff administration path (owner decisions O-13, S-02). Runs only in the one-shot {@code provision}
 * process as {@code sdlc_provisioner}; never in the API, the worker or an agent.
 *
 * <p>Every requesting or approving administrator proves presence with a current TOTP code (replay-guarded). The
 * database triggers of changeset 0017 enforce the same two-person and bootstrap rules independently of this class.
 * Nothing secret is logged or audited: audit rows carry ids and codes only.
 */
class StaffProvisioning {

    static final Duration DEFAULT_ENROLMENT_WINDOW = Duration.ofHours(72);
    private static final Duration MAX_ENROLMENT_WINDOW = Duration.ofDays(7);

    /** A refused provisioning command; the message is safe to print (no secrets). */
    static final class ProvisioningException extends RuntimeException {
        ProvisioningException(String message) {
            super(message);
        }
    }

    record Admin(String email, String totpCode) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwordEncoder;
    private final MfaCipher cipher;
    private final String environment;
    private final Clock clock;

    StaffProvisioning(JdbcClient jdbc, PlatformTransactionManager transactionManager, PasswordEncoder passwordEncoder,
                      MfaCipher cipher, String environment, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
        this.environment = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        this.clock = clock;
    }

    boolean developmentOrTest() {
        return environment.equals("development") || environment.equals("test");
    }

    /** Creates an ACTIVE staff identity with no capability and no assignment, with an MFA enrolment window. */
    UUID createStaff(String email, String displayName, String password, Duration enrolmentWindow) {
        String normalised = requireEmail(email);
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new ProvisioningException("display name is required (at most 200 characters)");
        }
        if (password == null || password.length() < 12 || password.length() > 200) {
            throw new ProvisioningException("password must be 12 to 200 characters");
        }
        Duration window = window(enrolmentWindow);
        return tx.execute(status -> {
            if (jdbc.sql("SELECT count(*) FROM app.staff_user WHERE lower(email) = lower(:email)")
                    .param("email", normalised).query(Integer.class).single() > 0) {
                throw new ProvisioningException("a staff member with this email already exists");
            }
            UUID id = jdbc.sql("""
                    INSERT INTO app.staff_user (email, display_name, password_hash, status, mfa_enrol_before)
                    VALUES (:email, :name, :hash, 'ACTIVE', clock_timestamp() + make_interval(secs => :seconds))
                    RETURNING id
                    """).param("email", normalised).param("name", displayName.strip())
                    .param("hash", passwordEncoder.encode(password)).param("seconds", window.toSeconds())
                    .query(UUID.class).single();
            audit(null, "STAFF_CREATED", "STAFF_USER", id.toString(), null);
            return id;
        });
    }

    /**
     * Development/test only: the first two administrators approve each other (O-13). Refused in any other
     * environment, so production has no administrator until the two-step ceremony is built and approved.
     */
    void bootstrapAdmins(Admin first, Admin second) {
        if (!developmentOrTest()) {
            auditIndependently(null, "STAFF_BOOTSTRAP", "DENIED");
            throw new ProvisioningException("bootstrap-admins is available only when sdlc.provisioning.environment is "
                    + "development or test; the production administrator ceremony is not implemented");
        }
        tx.executeWithoutResult(status -> {
            UUID a = proveAdminPresence(first, false);
            UUID b = proveAdminPresence(second, false);
            distinct(a, b);
            insertGrant(a, StaffCapability.STAFF_ADMIN, null, b, true);
            insertGrant(b, StaffCapability.STAFF_ADMIN, null, a, true);
            audit(null, "STAFF_BOOTSTRAP", "STAFF_USER", a + "," + b, null);
        });
    }

    void grantCapability(String staffEmail, StaffCapability capability, Admin requester, Admin approver) {
        tx.executeWithoutResult(status -> {
            UUID grantee = staffId(staffEmail);
            UUID r = proveAdminPresence(requester, true);
            UUID p = proveAdminPresence(approver, true);
            distinct(r, p, grantee);
            insertGrant(grantee, capability, r, p, false);
            audit(r, "STAFF_CAPABILITY_GRANTED", "STAFF_USER", grantee + ":" + capability.name(), null);
        });
    }

    void assignTenant(String staffEmail, String tenantSlug, Admin requester, Admin approver) {
        tx.executeWithoutResult(status -> {
            UUID assignee = staffId(staffEmail);
            UUID tenant = tenantId(tenantSlug);
            UUID r = proveAdminPresence(requester, true);
            UUID p = proveAdminPresence(approver, true);
            distinct(r, p, assignee);
            jdbc.sql("""
                    INSERT INTO app.staff_tenant_assignment (staff_id, assigned_tenant_id, requested_by, approved_by, operator)
                    VALUES (:staff, :tenant, :requester, :approver, current_user)
                    """).param("staff", assignee).param("tenant", tenant).param("requester", r).param("approver", p)
                    .update();
            audit(r, "STAFF_TENANT_ASSIGNED", "STAFF_USER", assignee.toString(), tenant);
        });
    }

    void revokeCapability(String staffEmail, StaffCapability capability, Admin admin) {
        tx.executeWithoutResult(status -> {
            UUID target = staffId(staffEmail);
            UUID a = proveAdminPresence(admin, true);
            int updated = jdbc.sql("""
                    UPDATE app.staff_capability_grant SET revoked_at = clock_timestamp(), revoked_by = :admin
                    WHERE staff_id = :staff AND capability = :capability AND revoked_at IS NULL
                    """).param("admin", a).param("staff", target).param("capability", capability.name()).update();
            if (updated == 0) {
                throw new ProvisioningException("no active grant to revoke");
            }
            audit(a, "STAFF_CAPABILITY_REVOKED", "STAFF_USER", target + ":" + capability.name(), null);
        });
    }

    void revokeAssignment(String staffEmail, String tenantSlug, Admin admin) {
        tx.executeWithoutResult(status -> {
            UUID target = staffId(staffEmail);
            UUID tenant = tenantId(tenantSlug);
            UUID a = proveAdminPresence(admin, true);
            int updated = jdbc.sql("""
                    UPDATE app.staff_tenant_assignment SET revoked_at = clock_timestamp(), revoked_by = :admin
                    WHERE staff_id = :staff AND assigned_tenant_id = :tenant AND revoked_at IS NULL
                    """).param("admin", a).param("staff", target).param("tenant", tenant).update();
            if (updated == 0) {
                throw new ProvisioningException("no active assignment to revoke");
            }
            audit(a, "STAFF_TENANT_REVOKED", "STAFF_USER", target.toString(), tenant);
        });
    }

    void disableStaff(String staffEmail, Admin admin) {
        tx.executeWithoutResult(status -> {
            UUID target = staffId(staffEmail);
            UUID a = proveAdminPresence(admin, true);
            jdbc.sql("UPDATE app.staff_user SET status = 'DISABLED' WHERE id = :id").param("id", target).update();
            audit(a, "STAFF_DISABLED", "STAFF_USER", target.toString(), null);
        });
    }

    /** Two administrators clear the target's MFA and open a new enrolment window (no MFA bypass, O-11). */
    void resetMfa(String staffEmail, Admin requester, Admin approver, Duration enrolmentWindow) {
        Duration window = window(enrolmentWindow);
        tx.executeWithoutResult(status -> {
            UUID target = staffId(staffEmail);
            UUID r = proveAdminPresence(requester, true);
            UUID p = proveAdminPresence(approver, true);
            distinct(r, p, target);
            jdbc.sql("""
                    UPDATE app.staff_user SET mfa_secret_enc = NULL, mfa_enrolled_at = NULL, totp_last_step = NULL,
                           mfa_enrol_before = clock_timestamp() + make_interval(secs => :seconds)
                    WHERE id = :id
                    """).param("seconds", window.toSeconds()).param("id", target).update();
            audit(r, "STAFF_MFA_RESET", "STAFF_USER", target.toString(), null);
        });
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    /** Verifies the administrator's current TOTP (consuming the step) and, if required, an active STAFF_ADMIN grant. */
    private UUID proveAdminPresence(Admin admin, boolean mustBeAdmin) {
        if (admin == null || admin.email() == null || admin.totpCode() == null) {
            throw new ProvisioningException("each administrator must be named with a current TOTP code");
        }
        record Row(UUID id, boolean active, String secret, boolean enrolled) {
        }
        Row row = jdbc.sql("""
                SELECT id, status = 'ACTIVE', mfa_secret_enc, mfa_enrolled_at IS NOT NULL FROM app.staff_user
                WHERE lower(email) = lower(:email)
                """).param("email", admin.email().trim())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getString(3),
                        rs.getBoolean(4)))
                .optional().orElseThrow(() -> new ProvisioningException("administrator presence not proven"));
        if (!row.active() || !row.enrolled()) {
            throw new ProvisioningException("administrator presence not proven");
        }
        OptionalLong step = Totp.verify(cipher.decrypt(row.id(), row.secret()), admin.totpCode(),
                clock.instant().getEpochSecond());
        if (step.isEmpty() || jdbc.sql("""
                UPDATE app.staff_user SET totp_last_step = :step
                WHERE id = :id AND (totp_last_step IS NULL OR totp_last_step < :step)
                """).param("step", step.getAsLong()).param("id", row.id()).update() != 1) {
            throw new ProvisioningException("administrator presence not proven");
        }
        if (mustBeAdmin && !Boolean.TRUE.equals(jdbc.sql("SELECT app.staff_is_admin(:id)").param("id", row.id())
                .query(Boolean.class).single())) {
            throw new ProvisioningException("administrator presence not proven");
        }
        return row.id();
    }

    private void insertGrant(UUID staff, StaffCapability capability, UUID requester, UUID approver, boolean bootstrap) {
        if (jdbc.sql("""
                SELECT count(*) FROM app.staff_capability_grant
                WHERE staff_id = :staff AND capability = :capability AND revoked_at IS NULL
                """).param("staff", staff).param("capability", capability.name()).query(Integer.class).single() > 0) {
            throw new ProvisioningException("the capability is already granted");
        }
        jdbc.sql("""
                INSERT INTO app.staff_capability_grant (staff_id, capability, bootstrap, requested_by, approved_by, operator)
                VALUES (:staff, :capability, :bootstrap, :requester, :approver, current_user)
                """).param("staff", staff).param("capability", capability.name()).param("bootstrap", bootstrap)
                .param("requester", requester).param("approver", approver).update();
    }

    private UUID staffId(String email) {
        return jdbc.sql("SELECT id FROM app.staff_user WHERE lower(email) = lower(:email)")
                .param("email", requireEmail(email)).query(UUID.class).optional()
                .orElseThrow(() -> new ProvisioningException("unknown staff member"));
    }

    private UUID tenantId(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ProvisioningException("tenant slug is required");
        }
        return jdbc.sql("SELECT id FROM app.tenant WHERE slug = :slug AND status = 'ACTIVE'")
                .param("slug", slug.trim().toLowerCase(Locale.ROOT)).query(UUID.class).optional()
                .orElseThrow(() -> new ProvisioningException("unknown or inactive tenant"));
    }

    private static void distinct(UUID... ids) {
        for (int i = 0; i < ids.length; i++) {
            for (int j = i + 1; j < ids.length; j++) {
                if (ids[i].equals(ids[j])) {
                    throw new ProvisioningException("requester, approver and target must be different people");
                }
            }
        }
    }

    private static Duration window(Duration requested) {
        Duration window = requested == null ? DEFAULT_ENROLMENT_WINDOW : requested;
        if (window.isNegative() || window.isZero() || window.compareTo(MAX_ENROLMENT_WINDOW) > 0) {
            throw new ProvisioningException("enrolment window must be between 1 hour and 7 days");
        }
        return window;
    }

    private static String requireEmail(String email) {
        if (email == null || !email.trim().matches("^[^@\\s]{1,64}@[^@\\s]{1,255}$")) {
            throw new ProvisioningException("a valid email is required");
        }
        return email.trim();
    }

    private void audit(UUID actor, String action, String targetType, String targetRef, UUID tenant) {
        jdbc.sql("""
                INSERT INTO app.platform_audit_event (actor_staff_id, action, target_type, target_ref, tenant_ref, result)
                VALUES (:actor, :action, :targetType, :targetRef, :tenant, 'SUCCESS')
                """).param("actor", actor).param("action", action).param("targetType", targetType)
                .param("targetRef", targetRef).param("tenant", tenant).update();
    }

    private void auditIndependently(UUID actor, String action, String result) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                INSERT INTO app.platform_audit_event (actor_staff_id, action, result) VALUES (:actor, :action, :result)
                """).param("actor", actor).param("action", action).param("result", result).update());
    }
}
