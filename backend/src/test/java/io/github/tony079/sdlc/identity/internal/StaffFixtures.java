package io.github.tony079.sdlc.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.support.TestDatabase;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Staff test fixtures. Staff are created through the real provisioning path (sdlc_provisioner). The two bootstrap
 * administrators exist once per test database (the trigger allows exactly two), so they are shared by all tests.
 * Administrators prove presence with codes computed on a test clock that only moves forward.
 */
public final class StaffFixtures {

    /** A staff member with an enrolled TOTP secret. */
    public record Enrolled(UUID id, String email, byte[] secret) {
    }

    private static final AtomicLong CLOCK = new AtomicLong(Instant.now().getEpochSecond());
    private static Enrolled adminA;
    private static Enrolled adminB;

    private static final MfaCipher CIPHER = new MfaCipher(TestDatabase.MFA_KEY);
    private static final Argon2PasswordEncoder ENCODER = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    private StaffFixtures() {
    }

    /** The provisioning service as deployed: provisioning login, given environment, the forward-only test clock. */
    static StaffProvisioning provisioning(String environment) {
        TestDatabase db = TestDatabase.get();
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
                environment, clock);
    }

    /** Advances the provisioning clock by two steps and returns a code for the given secret at the new time. */
    static String nextCode(byte[] secret) {
        return Totp.code(secret, Totp.step(CLOCK.get()));
    }

    static void advance() {
        CLOCK.addAndGet(Duration.ofSeconds(90).toSeconds());
    }

    public static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@staff.test";
    }

    /** Creates an ACTIVE staff member with an open enrolment window, through the provisioning path. */
    public static UUID createStaff(String email, String password) {
        return provisioning("test").createStaff(email, "Staff " + email, password, null);
    }

    /** Enrols a TOTP secret directly (inside the open window) so the member can approve or log in. */
    public static Enrolled createEnrolled(String email, String password) {
        UUID id = createStaff(email, password);
        byte[] secret = Totp.newSecret();
        TestDatabase.get().admin().update("""
                UPDATE app.staff_user SET mfa_secret_enc = ?, mfa_enrolled_at = clock_timestamp() WHERE id = ?
                """, CIPHER.encrypt(id, secret), id);
        return new Enrolled(id, email, secret);
    }

    /** The two bootstrap administrators of this test database (created on first use). */
    static synchronized Enrolled[] admins() {
        if (adminA == null) {
            Enrolled a = createEnrolled(uniqueEmail("admin-a"), "admin-password-" + UUID.randomUUID());
            Enrolled b = createEnrolled(uniqueEmail("admin-b"), "admin-password-" + UUID.randomUUID());
            advance();
            provisioning("test").bootstrapAdmins(new StaffProvisioning.Admin(a.email(), nextCode(a.secret())),
                    new StaffProvisioning.Admin(b.email(), nextCode(b.secret())));
            adminA = a;
            adminB = b;
        }
        return new Enrolled[] {adminA, adminB};
    }

    static synchronized StaffProvisioning.Admin admin(int index) {
        Enrolled admin = admins()[index];
        return new StaffProvisioning.Admin(admin.email(), nextCode(admin.secret()));
    }

    /** Grants a capability through the two-person path. */
    public static synchronized void grant(String email, StaffCapability capability) {
        admins();
        advance();
        provisioning("test").grantCapability(email, capability, admin(0), admin(1));
    }

    /** Assigns a tenant through the two-person path. */
    public static synchronized void assign(String email, String tenantSlug) {
        admins();
        advance();
        provisioning("test").assignTenant(email, tenantSlug, admin(0), admin(1));
    }

    public static synchronized void revokeAssignment(String email, String tenantSlug) {
        admins();
        advance();
        provisioning("test").revokeAssignment(email, tenantSlug, admin(0));
    }

    public static synchronized void revokeCapability(String email, StaffCapability capability) {
        admins();
        advance();
        provisioning("test").revokeCapability(email, capability, admin(0));
    }

    public static synchronized void disable(String email) {
        admins();
        advance();
        provisioning("test").disableStaff(email, admin(0));
    }

    /** A TOTP code for the real current time shifted by {@code steps}, for API logins. */
    public static String apiCode(byte[] secret, int steps) {
        return Totp.code(secret, Totp.step(Instant.now().getEpochSecond()) + steps);
    }

    public static byte[] fromBase32(String value) {
        return Totp.fromBase32(value);
    }
}
