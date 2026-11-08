package io.github.tony079.sdlc.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
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
    private final boolean productionBootstrapEnabled;
    private final String authorizationSha256;

    static final Duration MAX_CEREMONY_TTL = Duration.ofHours(24);

    StaffProvisioning(JdbcClient jdbc, PlatformTransactionManager transactionManager, PasswordEncoder passwordEncoder,
                      MfaCipher cipher, String environment, Clock clock) {
        this(jdbc, transactionManager, passwordEncoder, cipher, environment, clock, false, null);
    }

    /**
     * @param productionBootstrapEnabled O-13: the INITIAL/RECOVERY ceremony stays disabled unless this is true
     * @param authorizationSha256        SHA-256 (hex) of the one-time ceremony authorisation the owner hands to the
     *                                   operator out of band; without it no ceremony can be opened
     */
    StaffProvisioning(JdbcClient jdbc, PlatformTransactionManager transactionManager, PasswordEncoder passwordEncoder,
                      MfaCipher cipher, String environment, Clock clock, boolean productionBootstrapEnabled,
                      String authorizationSha256) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
        this.environment = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        this.clock = clock;
        this.productionBootstrapEnabled = productionBootstrapEnabled;
        this.authorizationSha256 = authorizationSha256 == null ? "" : authorizationSha256.trim().toLowerCase(Locale.ROOT);
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
            // Development/test: both participants are present in this call; the same ceremony record is used.
            UUID ceremony = insertCeremony("DEVELOPMENT", a, b, Duration.ofMinutes(10));
            jdbc.sql("""
                    UPDATE app.staff_bootstrap_ceremony SET approved_a_at = clock_timestamp(), approved_b_at = clock_timestamp()
                    WHERE id = :id
                    """).param("id", ceremony).update();
            completeCeremony(ceremony, a, b);
            audit(null, "STAFF_BOOTSTRAP", "STAFF_USER", a + "," + b, null);
        });
    }

    // --- O-13 production ceremony ----------------------------------------------------------------------------

    /**
     * Opens an INITIAL or RECOVERY ceremony naming two distinct, MFA-enrolled participants. Refused unless production
     * bootstrap is enabled by configuration and the operator presents the one-time authorisation whose SHA-256 is
     * configured. The database additionally refuses INITIAL once any administrator exists, RECOVERY while two active
     * administrators exist, a second open ceremony, and a lifetime over 24 hours. Returns the ceremony id.
     */
    UUID openCeremony(String purpose, String firstEmail, String secondEmail, Duration ttl, String authorization) {
        if (!"INITIAL".equals(purpose) && !"RECOVERY".equals(purpose)) {
            throw new ProvisioningException("purpose must be INITIAL or RECOVERY");
        }
        if (!productionBootstrapEnabled || authorizationSha256.isEmpty()) {
            refuse("CEREMONY_DISABLED");
            throw new ProvisioningException("the production bootstrap ceremony is disabled: set "
                    + "sdlc.provisioning.production-bootstrap.enabled and its authorization-sha256");
        }
        if (authorization == null || !MessageDigest.isEqual(sha256(authorization).getBytes(StandardCharsets.US_ASCII),
                authorizationSha256.getBytes(StandardCharsets.US_ASCII))) {
            refuse("CEREMONY_AUTHORIZATION_INVALID");
            throw new ProvisioningException("ceremony authorisation not accepted");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_CEREMONY_TTL) > 0) {
            throw new ProvisioningException("ceremony lifetime must be between 1 minute and 24 hours");
        }
        expireLapsed();
        try {
            return tx.execute(status -> {
                UUID a = staffId(firstEmail);
                UUID b = staffId(secondEmail);
                distinct(a, b);
                UUID id = insertCeremony(purpose, a, b, ttl);
                audit(null, "BOOTSTRAP_CEREMONY_OPENED", "BOOTSTRAP_CEREMONY", id.toString(), null);
                securityEvent("ADMIN_BOOTSTRAP", "HIGH", null, "OPENED:" + purpose + ":" + id);
                return id;
            });
        } catch (org.springframework.dao.DataAccessException e) {
            refuse("CEREMONY_REFUSED");
            throw new ProvisioningException("the ceremony cannot be opened now (closed, already open or invalid)");
        }
    }

    /**
     * One participant approves, authenticated with their own password and current TOTP code. The first approval is
     * recorded; the second (by the other participant, before expiry) grants STAFF_ADMIN to both and completes the
     * ceremony, which can never be used again. Non-participants, repeated approvals and expired ceremonies are refused.
     */
    String approveCeremony(UUID ceremonyId, String email, String password, String totpCode) {
        expireLapsed();
        try {
            return tx.execute(status -> {
                record Ceremony(String purpose, UUID a, UUID b, boolean approvedA, boolean approvedB, String state,
                                boolean expired) {
                }
                Ceremony c = jdbc.sql("""
                        SELECT purpose, participant_a, participant_b, approved_a_at IS NOT NULL, approved_b_at IS NOT NULL,
                               status, clock_timestamp() >= expires_at FROM app.staff_bootstrap_ceremony WHERE id = :id
                        FOR UPDATE
                        """).param("id", ceremonyId).query((rs, n) -> new Ceremony(rs.getString(1),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getBoolean(4), rs.getBoolean(5),
                        rs.getString(6), rs.getBoolean(7))).optional()
                        .orElseThrow(() -> new ProvisioningException("ceremony approval refused"));
                if ("DEVELOPMENT".equals(c.purpose()) || !"OPEN".equals(c.state()) || c.expired()) {
                    throw new ProvisioningException("ceremony approval refused");
                }
                UUID participant = proveParticipant(email, password, totpCode);
                boolean isA = participant.equals(c.a());
                if (!isA && !participant.equals(c.b())) {
                    throw new ProvisioningException("ceremony approval refused");
                }
                if ((isA && c.approvedA()) || (!isA && c.approvedB())) {
                    throw new ProvisioningException("this participant has already approved");
                }
                jdbc.sql("UPDATE app.staff_bootstrap_ceremony SET " + (isA ? "approved_a_at" : "approved_b_at")
                        + " = clock_timestamp() WHERE id = :id").param("id", ceremonyId).update();
                audit(participant, "BOOTSTRAP_CEREMONY_APPROVED", "BOOTSTRAP_CEREMONY", ceremonyId.toString(), null);
                securityEvent("ADMIN_BOOTSTRAP", "HIGH", participant.toString(), "APPROVED:" + ceremonyId);
                if ((isA ? c.approvedB() : c.approvedA())) {
                    completeCeremony(ceremonyId, c.a(), c.b());
                    audit(participant, "BOOTSTRAP_CEREMONY_COMPLETED", "BOOTSTRAP_CEREMONY", ceremonyId.toString(), null);
                    securityEvent("ADMIN_BOOTSTRAP", "HIGH", participant.toString(), "COMPLETED:" + ceremonyId);
                    return "COMPLETED";
                }
                return "APPROVED";
            });
        } catch (ProvisioningException e) {
            refuse("CEREMONY_APPROVAL_REFUSED");
            throw e;
        } catch (org.springframework.dao.DataAccessException e) {
            refuse("CEREMONY_APPROVAL_REFUSED");
            throw new ProvisioningException("ceremony approval refused");
        }
    }

    /** A lapsed ceremony becomes EXPIRED (final), so it can never be completed and never blocks a new one. */
    private void expireLapsed() {
        tx.executeWithoutResult(status -> {
            int expired = jdbc.sql("""
                    UPDATE app.staff_bootstrap_ceremony SET status = 'EXPIRED'
                    WHERE status = 'OPEN' AND clock_timestamp() >= expires_at
                    """).update();
            if (expired > 0) {
                audit(null, "BOOTSTRAP_CEREMONY_EXPIRED", "BOOTSTRAP_CEREMONY", String.valueOf(expired), null);
            }
        });
    }

    private UUID insertCeremony(String purpose, UUID a, UUID b, Duration ttl) {
        return jdbc.sql("""
                INSERT INTO app.staff_bootstrap_ceremony (purpose, participant_a, participant_b, expires_at, operator)
                VALUES (:purpose, :a, :b, clock_timestamp() + make_interval(secs => :seconds), current_user) RETURNING id
                """).param("purpose", purpose).param("a", a).param("b", b).param("seconds", ttl.toSeconds())
                .query(UUID.class).single();
    }

    /** Grants STAFF_ADMIN to each participant not already an administrator (RECOVERY keeps the survivor's grant). */
    private void completeCeremony(UUID ceremony, UUID a, UUID b) {
        for (UUID[] pair : new UUID[][] {{a, b}, {b, a}}) {
            if (Boolean.TRUE.equals(jdbc.sql("SELECT app.staff_is_admin(:id)").param("id", pair[0])
                    .query(Boolean.class).single())) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO app.staff_capability_grant (staff_id, capability, bootstrap, approved_by, operator, ceremony_id)
                    VALUES (:staff, 'STAFF_ADMIN', true, :approver, current_user, :ceremony)
                    """).param("staff", pair[0]).param("approver", pair[1]).param("ceremony", ceremony).update();
            securityEvent("PRIVILEGE_CHANGED", "HIGH", pair[1].toString(), "STAFF_ADMIN:" + pair[0]);
        }
        jdbc.sql("UPDATE app.staff_bootstrap_ceremony SET status = 'COMPLETED', completed_at = clock_timestamp() WHERE id = :id")
                .param("id", ceremony).update();
    }

    /** Password and current TOTP of an active, enrolled staff member (no administrator grant required). */
    private UUID proveParticipant(String email, String password, String totpCode) {
        String hash = jdbc.sql("SELECT password_hash FROM app.staff_user WHERE lower(email) = lower(:email)")
                .param("email", email == null ? "" : email.trim()).query(String.class).optional().orElse(null);
        if (hash == null || password == null || !passwordEncoder.matches(password, hash)) {
            throw new ProvisioningException("ceremony approval refused");
        }
        try {
            return proveAdminPresence(new Admin(email, totpCode), false);
        } catch (ProvisioningException e) {
            throw new ProvisioningException("ceremony approval refused");
        }
    }

    private void refuse(String reason) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO app.platform_audit_event (action, target_type, target_ref, result)
                    VALUES ('BOOTSTRAP_CEREMONY_REFUSED', 'BOOTSTRAP_CEREMONY', :reason, 'DENIED')
                    """).param("reason", reason).update();
            securityEvent("ADMIN_BOOTSTRAP", "HIGH", null, "REFUSED:" + reason);
        });
    }

    private void securityEvent(String type, String severity, String actor, String target) {
        jdbc.sql("""
                INSERT INTO app.platform_security_event (type, severity, actor_ref, target_ref)
                VALUES (:type, :severity, :actor, :target)
                """).param("type", type).param("severity", severity).param("actor", actor)
                .param("target", target.length() > 128 ? target.substring(0, 128) : target).update();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void grantCapability(String staffEmail, StaffCapability capability, Admin requester, Admin approver) {
        tx.executeWithoutResult(status -> {
            UUID grantee = staffId(staffEmail);
            UUID r = proveAdminPresence(requester, true);
            UUID p = proveAdminPresence(approver, true);
            distinct(r, p, grantee);
            insertGrant(grantee, capability, r, p, false);
            audit(r, "STAFF_CAPABILITY_GRANTED", "STAFF_USER", grantee + ":" + capability.name(), null);
            securityEvent("PRIVILEGE_CHANGED", "HIGH", r.toString(), "GRANT:" + capability.name() + ":" + grantee);
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
            securityEvent("PRIVILEGE_CHANGED", "MEDIUM", r.toString(), "ASSIGN:" + assignee);
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
            securityEvent("PRIVILEGE_CHANGED", "MEDIUM", a.toString(), "REVOKE:" + capability.name() + ":" + target);
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
            securityEvent("PRIVILEGE_CHANGED", "MEDIUM", a.toString(), "UNASSIGN:" + target);
        });
    }

    void disableStaff(String staffEmail, Admin admin) {
        tx.executeWithoutResult(status -> {
            UUID target = staffId(staffEmail);
            UUID a = proveAdminPresence(admin, true);
            jdbc.sql("UPDATE app.staff_user SET status = 'DISABLED' WHERE id = :id").param("id", target).update();
            audit(a, "STAFF_DISABLED", "STAFF_USER", target.toString(), null);
            securityEvent("PRIVILEGE_CHANGED", "MEDIUM", a.toString(), "DISABLE:" + target);
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
            securityEvent("PRIVILEGE_CHANGED", "HIGH", r.toString(), "MFA_RESET:" + target);
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
