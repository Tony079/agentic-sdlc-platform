package io.github.tony079.sdlc.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import io.github.tony079.sdlc.identity.internal.StaffProvisioning.ProvisioningException;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * O-13 production administrator bootstrap (owner decision 2026-11-08), on a fresh database where no administrator has
 * ever existed: disabled until configured, authorisation required, two separately authenticated approvals, expiry,
 * single use, recovery, and the database refusing every shortcut.
 */
class BootstrapCeremonyIT {

    private static final String AUTHORISATION = "owner-ceremony-" + UUID.randomUUID();
    private static final String PASSWORD = "participant-password-" + UUID.randomUUID();
    private static final Argon2PasswordEncoder ENCODER = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private static final MfaCipher CIPHER = new MfaCipher(TestDatabase.MFA_KEY);
    private static final AtomicLong CLOCK = new AtomicLong(Instant.now().getEpochSecond());

    private static TestDatabase db;
    private static JdbcTemplate admin;

    private record Person(UUID id, String email, byte[] secret) {
    }

    @BeforeAll
    static void freshDatabase() {
        db = TestDatabase.fresh();
        admin = db.admin();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.staff_capability_grant", Integer.class)).isZero();
    }

    private static StaffProvisioning service(boolean enabled, String hash) {
        DriverManagerDataSource ds = new DriverManagerDataSource(db.appUrl(), db.provisionerUser(),
                db.provisionerPassword());
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochSecond(CLOCK.get());
            }
        };
        return new StaffProvisioning(JdbcClient.create(ds), new DataSourceTransactionManager(ds), ENCODER, CIPHER,
                "production", clock, enabled, hash);
    }

    private static StaffProvisioning enabled() {
        return service(true, StaffProvisioning.sha256(AUTHORISATION));
    }

    private static Person person(String prefix) {
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@staff.test";
        UUID id = enabled().createStaff(email, "Participant", PASSWORD, null);
        byte[] secret = Totp.newSecret();
        admin.update("UPDATE app.staff_user SET mfa_secret_enc = ?, mfa_enrolled_at = clock_timestamp() WHERE id = ?",
                CIPHER.encrypt(id, secret), id);
        return new Person(id, email, secret);
    }

    private static String code(Person p) {
        CLOCK.addAndGet(90);
        return Totp.code(p.secret(), Totp.step(CLOCK.get()));
    }

    private static List<String> audit() {
        return admin.queryForList("SELECT action FROM app.platform_audit_event ORDER BY occurred_at", String.class);
    }

    @Test
    void ceremonyLifecycle() throws Exception {
        Person a = person("a");
        Person b = person("b");
        Person outsider = person("c");

        // Disabled until configured; a wrong authorisation or lifetime is refused; nothing is created.
        assertThatThrownBy(() -> service(false, StaffProvisioning.sha256(AUTHORISATION))
                .openCeremony("INITIAL", a.email(), b.email(), Duration.ofMinutes(30), AUTHORISATION))
                .isInstanceOf(ProvisioningException.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> service(true, "").openCeremony("INITIAL", a.email(), b.email(),
                Duration.ofMinutes(30), AUTHORISATION)).hasMessageContaining("disabled");
        assertThatThrownBy(() -> enabled().openCeremony("INITIAL", a.email(), b.email(), Duration.ofMinutes(30),
                "guessed-authorisation")).hasMessageContaining("not accepted");
        assertThatThrownBy(() -> enabled().openCeremony("INITIAL", a.email(), b.email(), Duration.ofHours(25),
                AUTHORISATION)).hasMessageContaining("24 hours");
        assertThatThrownBy(() -> enabled().openCeremony("INITIAL", a.email(), a.email(), Duration.ofMinutes(30),
                AUTHORISATION)).hasMessageContaining("different people");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.staff_bootstrap_ceremony", Integer.class)).isZero();

        // Expiry: an approval after the deadline is refused and the ceremony becomes EXPIRED (never completes).
        UUID lapsed = enabled().openCeremony("INITIAL", a.email(), b.email(), Duration.ofMinutes(30), AUTHORISATION);
        assertThat(enabled().approveCeremony(lapsed, a.email(), PASSWORD, code(a))).isEqualTo("APPROVED");
        try (Connection c = db.adminConnection(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.executeUpdate("UPDATE app.staff_bootstrap_ceremony SET opened_at = now() - interval '2 hours', "
                    + "expires_at = now() - interval '1 hour' WHERE id = '" + lapsed + "'");
        }
        assertThatThrownBy(() -> enabled().approveCeremony(lapsed, b.email(), PASSWORD, code(b)))
                .hasMessageContaining("refused");
        assertThat(admin.queryForObject("SELECT status FROM app.staff_bootstrap_ceremony WHERE id = ?", String.class,
                lapsed)).isEqualTo("EXPIRED");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.staff_capability_grant", Integer.class)).isZero();

        // The real ceremony.
        UUID id = enabled().openCeremony("INITIAL", a.email(), b.email(), Duration.ofMinutes(30), AUTHORISATION);
        // Only one ceremony may be open.
        assertThatThrownBy(() -> enabled().openCeremony("INITIAL", a.email(), outsider.email(), Duration.ofMinutes(30),
                AUTHORISATION)).hasMessageContaining("cannot be opened");
        // A non-participant, a wrong password and a wrong code are refused.
        assertThatThrownBy(() -> enabled().approveCeremony(id, outsider.email(), PASSWORD, code(outsider)))
                .hasMessageContaining("refused");
        assertThatThrownBy(() -> enabled().approveCeremony(id, b.email(), "wrong-password-123", code(b)))
                .hasMessageContaining("refused");
        assertThatThrownBy(() -> enabled().approveCeremony(id, b.email(), PASSWORD, "000000"))
                .hasMessageContaining("refused");
        assertThat(enabled().approveCeremony(id, a.email(), PASSWORD, code(a))).isEqualTo("APPROVED");
        // The same person cannot approve twice (independence).
        assertThatThrownBy(() -> enabled().approveCeremony(id, a.email(), PASSWORD, code(a)))
                .hasMessageContaining("already approved");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.staff_capability_grant", Integer.class)).isZero();
        assertThat(enabled().approveCeremony(id, b.email(), PASSWORD, code(b))).isEqualTo("COMPLETED");

        // Each administrator is approved by the other and linked to the ceremony; no other grant exists.
        assertThat(admin.queryForList("""
                SELECT staff_id::text || '>' || approved_by::text FROM app.staff_capability_grant
                WHERE capability = 'STAFF_ADMIN' AND bootstrap AND ceremony_id = ? ORDER BY 1
                """, String.class, id)).containsExactlyInAnyOrder(a.id() + ">" + b.id(), b.id() + ">" + a.id());
        // Single use: no replay of the completed ceremony, no second INITIAL ceremony.
        assertThatThrownBy(() -> enabled().approveCeremony(id, b.email(), PASSWORD, code(b)))
                .hasMessageContaining("refused");
        assertThatThrownBy(() -> enabled().openCeremony("INITIAL", a.email(), outsider.email(), Duration.ofMinutes(30),
                AUTHORISATION)).hasMessageContaining("cannot be opened");
        // Recovery is refused while two administrators are active.
        assertThatThrownBy(() -> enabled().openCeremony("RECOVERY", a.email(), outsider.email(), Duration.ofMinutes(30),
                AUTHORISATION)).hasMessageContaining("cannot be opened");
        assertThat(audit()).contains("BOOTSTRAP_CEREMONY_OPENED", "BOOTSTRAP_CEREMONY_APPROVED",
                "BOOTSTRAP_CEREMONY_COMPLETED", "BOOTSTRAP_CEREMONY_EXPIRED", "BOOTSTRAP_CEREMONY_REFUSED");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.platform_security_event WHERE type = 'ADMIN_BOOTSTRAP'
                """, Integer.class)).isPositive();

        // Controlled recovery: B is disabled by A (an administrator); A and a replacement run a RECOVERY ceremony.
        enabled().disableStaff(b.email(), new StaffProvisioning.Admin(a.email(), code(a)));
        Person replacement = person("d");
        UUID recovery = enabled().openCeremony("RECOVERY", a.email(), replacement.email(), Duration.ofMinutes(30),
                AUTHORISATION);
        assertThat(enabled().approveCeremony(recovery, replacement.email(), PASSWORD, code(replacement)))
                .isEqualTo("APPROVED");
        assertThat(enabled().approveCeremony(recovery, a.email(), PASSWORD, code(a))).isEqualTo("COMPLETED");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.staff_capability_grant WHERE capability = 'STAFF_ADMIN' AND revoked_at IS NULL
                  AND staff_id = ?""", Integer.class, replacement.id())).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesBootstrapShortcuts() throws Exception {
        Person x = person("x");
        Person y = person("y");
        try (Connection c = db.provisionerConnection(); Statement st = c.createStatement()) {
            // A bootstrap grant without a ceremony.
            assertThat(catchThrowable(() -> st.executeUpdate("""
                    INSERT INTO app.staff_capability_grant (staff_id, capability, bootstrap, approved_by, operator)
                    VALUES ('%s', 'STAFF_ADMIN', true, '%s', 'test')""".formatted(x.id(), y.id()))))
                    .hasMessageContaining("bootstrap is closed");
            // A one-person ceremony, and a ceremony longer than 24 hours.
            assertThat(catchThrowable(() -> st.executeUpdate("""
                    INSERT INTO app.staff_bootstrap_ceremony (purpose, participant_a, participant_b, expires_at, operator)
                    VALUES ('RECOVERY', '%s', '%s', now() + interval '1 hour', 'test')""".formatted(x.id(), x.id()))))
                    .hasMessageContaining("check constraint");
            assertThat(catchThrowable(() -> st.executeUpdate("""
                    INSERT INTO app.staff_bootstrap_ceremony (purpose, participant_a, participant_b, expires_at, operator)
                    VALUES ('RECOVERY', '%s', '%s', now() + interval '25 hours', 'test')""".formatted(x.id(), y.id()))))
                    .hasMessageContaining("check constraint");
        }
        // The API login cannot open or approve ceremonies.
        try (Connection c = db.appConnection(); Statement st = c.createStatement()) {
            assertThat(catchThrowable(() -> st.executeUpdate("""
                    INSERT INTO app.staff_bootstrap_ceremony (purpose, participant_a, participant_b, expires_at, operator)
                    VALUES ('RECOVERY', '%s', '%s', now() + interval '1 hour', 'x')""".formatted(x.id(), y.id()))))
                    .hasMessageContaining("permission denied");
        }
    }
}
