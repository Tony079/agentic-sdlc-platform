package io.github.tony079.sdlc.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import io.github.tony079.sdlc.SdlcPlatformApplication;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.identity.internal.StaffProvisioning.Admin;
import io.github.tony079.sdlc.identity.internal.StaffProvisioning.ProvisioningException;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O-13 / S-02: the provisioning path (as sdlc_provisioner) and the database rules that bind it and the runtime logins.
 */
class StaffProvisioningIT {

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();

    @Test
    void createStaffHasNoCapabilityNoAssignmentAndAnOpenEnrolmentWindow() {
        String email = StaffFixtures.uniqueEmail("plain");
        UUID id = StaffFixtures.createStaff(email, "a-long-password-1");
        assertThat(admin.queryForObject("SELECT status FROM app.staff_user WHERE id = ?", String.class, id))
                .isEqualTo("ACTIVE");
        assertThat(admin.queryForObject("SELECT password_hash FROM app.staff_user WHERE id = ?", String.class, id))
                .startsWith("$argon2id$").doesNotContain("a-long-password-1");
        assertThat(admin.queryForObject(
                "SELECT mfa_enrol_before > now() + interval '71 hours' FROM app.staff_user WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.staff_capability_grant WHERE staff_id = ?",
                Integer.class, id)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.staff_tenant_assignment WHERE staff_id = ?",
                Integer.class, id)).isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.platform_audit_event WHERE action = 'STAFF_CREATED' AND target_ref = ?
                """, Integer.class, id.toString())).isOne();

        assertThatThrownBy(() -> StaffFixtures.createStaff(email.toUpperCase(), "a-long-password-1"))
                .isInstanceOf(ProvisioningException.class);
        assertThatThrownBy(() -> StaffFixtures.createStaff(StaffFixtures.uniqueEmail("short"), "short"))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void bootstrapIsRefusedOutsideDevelopmentAndTest() {
        Enrolled a = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("prod-a"), "a-long-password-1");
        Enrolled b = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("prod-b"), "a-long-password-1");
        for (String environment : new String[] {"production", "", "staging"}) {
            StaffFixtures.advance();
            assertThatThrownBy(() -> StaffFixtures.provisioning(environment).bootstrapAdmins(
                    new Admin(a.email(), StaffFixtures.nextCode(a.secret())),
                    new Admin(b.email(), StaffFixtures.nextCode(b.secret()))))
                    .isInstanceOf(ProvisioningException.class).hasMessageContaining("not implemented");
        }
        assertThat(admin.queryForObject("SELECT count(*) FROM app.staff_capability_grant WHERE staff_id IN (?, ?)",
                Integer.class, a.id(), b.id())).isZero();
    }

    @Test
    void bootstrapHappensOnceAndIsThenClosed() {
        Enrolled[] admins = StaffFixtures.admins();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.staff_capability_grant WHERE capability = 'STAFF_ADMIN' AND bootstrap
                """, Integer.class)).isEqualTo(2);
        // Each bootstrap grant is approved by the other administrator.
        assertThat(admin.queryForObject("SELECT approved_by FROM app.staff_capability_grant WHERE staff_id = ?",
                UUID.class, admins[0].id())).isEqualTo(admins[1].id());

        Enrolled c = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("late-a"), "a-long-password-1");
        Enrolled d = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("late-b"), "a-long-password-1");
        StaffFixtures.advance();
        assertThatThrownBy(() -> StaffFixtures.provisioning("test").bootstrapAdmins(
                new Admin(c.email(), StaffFixtures.nextCode(c.secret())),
                new Admin(d.email(), StaffFixtures.nextCode(d.secret()))))
                .hasMessageContaining("bootstrap is closed");
    }

    @Test
    void grantNeedsTwoDistinctAdministratorsWithValidFreshCodes() {
        StaffFixtures.admins();
        Enrolled target = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("grantee"), "a-long-password-1");
        StaffProvisioning provisioning = StaffFixtures.provisioning("test");

        StaffFixtures.advance();
        Admin a = StaffFixtures.admin(0);
        // The same administrator as requester and approver.
        assertThatThrownBy(() -> provisioning.grantCapability(target.email(), StaffCapability.REPOSITORY_REGISTER,
                a, StaffFixtures.admin(0))).isInstanceOf(ProvisioningException.class);

        // A wrong code.
        StaffFixtures.advance();
        assertThatThrownBy(() -> provisioning.grantCapability(target.email(), StaffCapability.REPOSITORY_REGISTER,
                new Admin(StaffFixtures.admins()[0].email(), "000000"), StaffFixtures.admin(1)))
                .hasMessageContaining("presence not proven");

        // A non-administrator as approver.
        StaffFixtures.advance();
        Enrolled outsider = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("outsider"), "a-long-password-1");
        assertThatThrownBy(() -> provisioning.grantCapability(target.email(), StaffCapability.REPOSITORY_REGISTER,
                StaffFixtures.admin(0), new Admin(outsider.email(), StaffFixtures.nextCode(outsider.secret()))))
                .hasMessageContaining("presence not proven");

        // A replayed code: the step was already consumed by a successful command.
        StaffFixtures.advance();
        Admin requester = StaffFixtures.admin(0);
        Admin approver = StaffFixtures.admin(1);
        provisioning.grantCapability(target.email(), StaffCapability.REPOSITORY_REGISTER, requester, approver);
        Enrolled second = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("second"), "a-long-password-1");
        assertThatThrownBy(() -> provisioning.grantCapability(second.email(), StaffCapability.REPOSITORY_REGISTER,
                requester, approver)).hasMessageContaining("presence not proven");

        assertThat(admin.queryForMap("""
                SELECT requested_by, approved_by, operator, bootstrap FROM app.staff_capability_grant
                WHERE staff_id = ? AND capability = 'REPOSITORY_REGISTER'
                """, target.id()))
                .containsEntry("requested_by", StaffFixtures.admins()[0].id())
                .containsEntry("approved_by", StaffFixtures.admins()[1].id())
                .containsEntry("operator", "sdlc_provisioner").containsEntry("bootstrap", false);

        // An administrator cannot grant to themselves (requester or approver equal to the grantee).
        StaffFixtures.advance();
        assertThatThrownBy(() -> provisioning.grantCapability(StaffFixtures.admins()[0].email(),
                StaffCapability.REPOSITORY_REGISTER, StaffFixtures.admin(0), StaffFixtures.admin(1)))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void assignmentRevocationAndMfaReset() {
        Enrolled target = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("assignee"), "a-long-password-1");
        StaffFixtures.assign(target.email(), "acme");
        UUID acme = db.tenantId("acme");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.staff_tenant_assignment
                WHERE staff_id = ? AND assigned_tenant_id = ? AND revoked_at IS NULL
                """, Integer.class, target.id(), acme)).isOne();
        StaffFixtures.revokeAssignment(target.email(), "acme");
        assertThat(admin.queryForObject("""
                SELECT revoked_by FROM app.staff_tenant_assignment WHERE staff_id = ? AND assigned_tenant_id = ?
                """, UUID.class, target.id(), acme)).isEqualTo(StaffFixtures.admins()[0].id());

        StaffFixtures.advance();
        StaffFixtures.provisioning("test").resetMfa(target.email(), StaffFixtures.admin(0), StaffFixtures.admin(1),
                Duration.ofHours(1));
        assertThat(admin.queryForMap(
                "SELECT mfa_secret_enc, mfa_enrolled_at, totp_last_step FROM app.staff_user WHERE id = ?", target.id()))
                .containsEntry("mfa_secret_enc", null).containsEntry("mfa_enrolled_at", null)
                .containsEntry("totp_last_step", null);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.platform_audit_event WHERE action = 'STAFF_MFA_RESET' AND target_ref = ?
                """, Integer.class, target.id().toString())).isOne();
    }

    @Test
    void provisionProfileStartsAsTheProvisioningLoginAndRefusesProductionBootstrap() {
        Enrolled a = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("cli-a"), "a-long-password-1");
        Enrolled b = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("cli-b"), "a-long-password-1");
        SdlcPlatformApplication.disableLiquibaseAnalytics();
        assertThatThrownBy(() -> new SpringApplicationBuilder(SdlcPlatformApplication.class)
                .profiles("provision")
                .run("--spring.datasource.url=" + db.appUrl(),
                        "--spring.datasource.password=" + db.provisionerPassword(),
                        "--sdlc.staff.mfa-key=" + TestDatabase.MFA_KEY,
                        "--sdlc.provisioning.environment=production",
                        "--logging.structured.format.console=",
                        "bootstrap-admins", "--first=" + a.email(), "--first-code=123456",
                        "--second=" + b.email(), "--second-code=123456")
                .close())
                .isInstanceOf(ProvisioningException.class).hasMessageContaining("not implemented");
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM app.platform_audit_event WHERE action = 'STAFF_BOOTSTRAP' AND result = 'DENIED'",
                Integer.class)).isPositive();
    }

    // --- S-02: database rules, independent of the Java code ------------------------------------------------------

    @Test
    void apiLoginCannotCreateStaffGrantOrAssign() throws SQLException {
        Enrolled[] admins = StaffFixtures.admins();
        Enrolled target = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("s02"), "a-long-password-1");
        UUID acme = db.tenantId("acme");
        try (Connection app = db.appConnection(); Statement statement = app.createStatement()) {
            for (String sql : new String[] {
                    "INSERT INTO app.staff_user (email, display_name, password_hash, status) VALUES ('x@y.z','x','h','ACTIVE')",
                    "INSERT INTO app.staff_capability_grant (staff_id, capability, requested_by, approved_by, operator) VALUES ('"
                            + target.id() + "','REPOSITORY_REGISTER','" + admins[0].id() + "','" + admins[1].id() + "','x')",
                    "INSERT INTO app.staff_tenant_assignment (staff_id, assigned_tenant_id, requested_by, approved_by, operator) VALUES ('"
                            + target.id() + "','" + acme + "','" + admins[0].id() + "','" + admins[1].id() + "','x')",
                    "UPDATE app.staff_capability_grant SET revoked_at = now(), revoked_by = '" + admins[0].id() + "'",
                    "UPDATE app.staff_tenant_assignment SET revoked_at = now(), revoked_by = '" + admins[0].id() + "'",
                    "UPDATE app.staff_user SET status = 'DISABLED' WHERE id = '" + target.id() + "'",
                    "UPDATE app.staff_user SET password_hash = 'x' WHERE id = '" + target.id() + "'",
                    "UPDATE app.staff_user SET mfa_enrol_before = now() + interval '1 day' WHERE id = '" + target.id() + "'",
                    "DELETE FROM app.staff_capability_grant",
                    "DELETE FROM app.platform_audit_event",
                    "UPDATE app.platform_audit_event SET result = 'SUCCESS'"}) {
                // GD-15: the API login may now write staff rows, but only as the execution of a two-person request
                // approved in the same transaction; outside one it is refused by a privilege or by a database guard.
                assertThatThrownBy(() -> statement.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .hasMessageMatching("(?s).*(permission denied|approved staff request|approved invitation"
                                + "|only through a staff link|already revoked|opens only through).*");
            }
            // An enrolled MFA secret cannot be replaced or cleared by the API login (trigger).
            assertThatThrownBy(() -> statement.execute("UPDATE app.staff_user SET mfa_secret_enc = 'abc' WHERE id = '"
                    + target.id() + "'")).hasMessageContaining("approved request");
            assertThatThrownBy(() -> statement.execute(
                    "UPDATE app.staff_user SET mfa_secret_enc = NULL, mfa_enrolled_at = NULL WHERE id = '"
                            + target.id() + "'")).hasMessageContaining("approved request");
        }
    }

    @Test
    void enrolmentOutsideTheWindowIsRefusedByTheDatabase() throws SQLException {
        String email = StaffFixtures.uniqueEmail("window");
        UUID id = StaffFixtures.createStaff(email, "a-long-password-1");
        try (Connection provisioner = db.provisionerConnection(); Statement statement = provisioner.createStatement()) {
            statement.execute("UPDATE app.staff_user SET mfa_enrol_before = now() - interval '1 minute' WHERE id = '"
                    + id + "'");
        }
        try (Connection app = db.appConnection(); Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.execute("UPDATE app.staff_user SET mfa_secret_enc = 'abc' WHERE id = '"
                    + id + "'")).hasMessageContaining("enrolment window is closed");
        }
    }

    @Test
    void provisioningLoginIsBoundByTheTriggers() throws SQLException {
        Enrolled[] admins = StaffFixtures.admins();
        Enrolled target = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("trig"), "a-long-password-1");
        Enrolled outsider = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("trig-out"), "a-long-password-1");
        try (Connection provisioner = db.provisionerConnection(); Statement statement = provisioner.createStatement()) {
            // A non-administrator requester, even through the privileged login.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO app.staff_capability_grant (staff_id, capability, requested_by, approved_by, operator)
                    VALUES ('%s', 'REPOSITORY_REGISTER', '%s', '%s', 'x')
                    """.formatted(target.id(), outsider.id(), admins[1].id())))
                    .hasMessageContaining("must both be active staff administrators");
            // Self-approval (CHECK).
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO app.staff_capability_grant (staff_id, capability, requested_by, approved_by, operator)
                    VALUES ('%s', 'REPOSITORY_REGISTER', '%s', '%s', 'x')
                    """.formatted(admins[0].id(), admins[1].id(), admins[0].id())))
                    .isInstanceOf(SQLException.class);
            // A third bootstrap grant.
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO app.staff_capability_grant (staff_id, capability, bootstrap, approved_by, operator)
                    VALUES ('%s', 'STAFF_ADMIN', true, '%s', 'x')
                    """.formatted(target.id(), admins[0].id()))).hasMessageContaining("bootstrap is closed");
            // Bootstrap only for STAFF_ADMIN (CHECK).
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO app.staff_capability_grant (staff_id, capability, bootstrap, approved_by, operator)
                    VALUES ('%s', 'REPOSITORY_REGISTER', true, '%s', 'x')
                    """.formatted(target.id(), admins[0].id()))).isInstanceOf(SQLException.class);
            // No deletion of grants or audit, by anyone at runtime.
            assertThatThrownBy(() -> statement.execute("DELETE FROM app.staff_capability_grant"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("DELETE FROM app.platform_audit_event"))
                    .hasMessageContaining("permission denied");
            // No tenant business data.
            assertThatThrownBy(() -> statement.execute("SELECT * FROM app.requirement"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("SELECT name FROM app.tenant"))
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void workerLoginHasNoStaffAccess() throws SQLException {
        try (Connection worker = db.workerConnection(); Statement statement = worker.createStatement()) {
            for (String table : new String[] {"staff_user", "staff_capability_grant", "staff_tenant_assignment",
                    "platform_audit_event"}) {
                assertThatThrownBy(() -> statement.execute("SELECT * FROM app." + table)).as(table)
                        .hasMessageContaining("permission denied");
            }
        }
    }

    @Test
    void provisioningLoginIsNotPrivileged() {
        assertThat(admin.queryForMap("""
                SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolinherit FROM pg_roles
                WHERE rolname = 'sdlc_provisioner'
                """)).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false)
                .containsEntry("rolcreaterole", false).containsEntry("rolcreatedb", false)
                .containsEntry("rolinherit", false);
        assertThat(admin.queryForObject("SELECT pg_has_role('sdlc_provisioner', 'sdlc_owner', 'MEMBER')",
                Boolean.class)).isFalse();
        assertThat(admin.queryForObject("SELECT has_schema_privilege('sdlc_provisioner', 'app', 'CREATE')",
                Boolean.class)).isFalse();
    }
}
