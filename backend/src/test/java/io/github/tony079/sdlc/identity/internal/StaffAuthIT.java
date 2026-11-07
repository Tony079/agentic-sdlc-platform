package io.github.tony079.sdlc.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;

/** O-06 / O-11: staff login with TOTP, pre-MFA isolation, replay, active tenant gating and re-validation. */
class StaffAuthIT extends ApiIntegrationTest {

    private static final String PASSWORD = "staff-password-" + UUID.randomUUID();

    private final TestDatabase db = TestDatabase.get();

    private ApiClient.Response login(ApiClient client, String email, String password) {
        return client.post("/api/v1/staff/auth/login", Map.of("email", email, "password", password));
    }

    /** Creates a staff member and enrols MFA through the API; returns the logged-in client and the secret. */
    private record Session(ApiClient client, byte[] secret, String email) {
    }

    private Session enrolledSession() {
        String email = StaffFixtures.uniqueEmail("api");
        StaffFixtures.createStaff(email, PASSWORD);
        ApiClient client = client();
        assertThat(login(client, email, PASSWORD).body().get("status").asString()).isEqualTo("MFA_ENROLMENT_REQUIRED");
        ApiClient.Response enrolment = client.post("/api/v1/staff/auth/mfa/enrolment", null);
        assertThat(enrolment.status()).isEqualTo(200);
        byte[] secret = StaffFixtures.fromBase32(enrolment.body().get("secret").asString());
        assertThat(enrolment.body().get("otpauthUri").asString()).startsWith("otpauth://totp/")
                .contains("secret=" + enrolment.body().get("secret").asString());
        ApiClient.Response confirmed = client.post("/api/v1/staff/auth/mfa/enrolment/confirm",
                Map.of("code", StaffFixtures.apiCode(secret, 0)));
        assertThat(confirmed.status()).as(confirmed.body().toString()).isEqualTo(200);
        return new Session(client, secret, email);
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() {
        String email = StaffFixtures.uniqueEmail("wrong");
        StaffFixtures.createStaff(email, PASSWORD);
        ApiClient.Response wrong = login(client(), email, "not-the-password");
        ApiClient.Response unknown = login(client(), StaffFixtures.uniqueEmail("nobody"), PASSWORD);
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(wrong.errorCode()).isEqualTo("INVALID_CREDENTIALS").isEqualTo(unknown.errorCode());
        // A tenant user's credentials are not staff credentials.
        assertThat(login(client(), "alice@acme.test", TestDatabase.SEED_PASSWORD).status()).isEqualTo(401);
    }

    @Test
    void beforeMfaNothingElseIsReachable() {
        String email = StaffFixtures.uniqueEmail("pending");
        StaffFixtures.createStaff(email, PASSWORD);
        ApiClient client = client();
        assertThat(login(client, email, PASSWORD).status()).isEqualTo(200);
        assertThat(client.get("/api/v1/staff/me").status()).isEqualTo(403);
        assertThat(client.put("/api/v1/staff/session/active-tenant", Map.of("tenantId", db.tenantId("acme")), Map.of())
                .status()).isEqualTo(403);
        assertThat(client.get("/api/v1/projects").status()).isEqualTo(403);
        assertThat(client.get("/api/v1/me").status()).isEqualTo(403);
        // Verify (for enrolled accounts) is refused for an unenrolled one.
        assertThat(client.post("/api/v1/staff/auth/mfa/verify", Map.of("code", "123456")).status()).isEqualTo(401);
    }

    @Test
    void enrolmentThenLoginWithMfaAndReplayIsRejected() {
        Session session = enrolledSession();
        ApiClient.Response me = session.client().get("/api/v1/staff/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("email").asString()).isEqualTo(session.email());
        assertThat(me.body().get("activeTenant").isNull()).isTrue();
        assertThat(me.body().get("capabilities")).isEmpty();
        // Staff never get client APIs.
        assertThat(session.client().get("/api/v1/projects").status()).isEqualTo(403);
        assertThat(session.client().get("/api/v1/me").status()).isEqualTo(403);
        assertThat(session.client().post("/api/v1/staff/auth/logout", null).status()).isEqualTo(204);
        assertThat(session.client().get("/api/v1/staff/me").status()).isEqualTo(401);

        ApiClient again = client();
        assertThat(login(again, session.email(), PASSWORD).body().get("status").asString()).isEqualTo("MFA_REQUIRED");
        // Enrolment is closed once enrolled.
        assertThat(again.post("/api/v1/staff/auth/mfa/enrolment", null).status()).isEqualTo(409);
        // The code used for enrolment cannot be used again (replay).
        ApiClient.Response replay = again.post("/api/v1/staff/auth/mfa/verify",
                Map.of("code", StaffFixtures.apiCode(session.secret(), 0)));
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.errorCode()).isEqualTo("MFA_FAILED");
        ApiClient.Response ok = again.post("/api/v1/staff/auth/mfa/verify",
                Map.of("code", StaffFixtures.apiCode(session.secret(), 1)));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(again.get("/api/v1/staff/me").status()).isEqualTo(200);

        Integer failures = db.admin().queryForObject("""
                SELECT count(*) FROM app.platform_security_event WHERE type = 'MFA_FAILED' AND actor_ref = ?
                """, Integer.class, ok.body().get("staffId").asString());
        assertThat(failures).isOne();
        // No secrets or codes in the audit trail.
        assertThat(db.admin().queryForList("SELECT coalesce(target_ref, '') FROM app.platform_audit_event",
                String.class)).noneMatch(ref -> ref.contains(StaffFixtures.apiCode(session.secret(), 1)));
    }

    @Test
    void wrongCodesAreThrottled() {
        String email = StaffFixtures.uniqueEmail("throttle");
        StaffFixtures.createStaff(email, PASSWORD);
        ApiClient client = client();
        login(client, email, PASSWORD);
        client.post("/api/v1/staff/auth/mfa/enrolment", null);
        int last = 0;
        for (int i = 0; i < 6; i++) {
            last = client.post("/api/v1/staff/auth/mfa/enrolment/confirm", Map.of("code", "000000")).status();
        }
        assertThat(last).isEqualTo(429);
    }

    @Test
    void closedEnrolmentWindowRefusesEnrolment() throws Exception {
        String email = StaffFixtures.uniqueEmail("closed");
        UUID id = StaffFixtures.createStaff(email, PASSWORD);
        try (Connection provisioner = db.provisionerConnection(); Statement statement = provisioner.createStatement()) {
            statement.execute("UPDATE app.staff_user SET mfa_enrol_before = now() - interval '1 minute' WHERE id = '"
                    + id + "'");
        }
        ApiClient client = client();
        login(client, email, PASSWORD);
        ApiClient.Response response = client.post("/api/v1/staff/auth/mfa/enrolment", null);
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo("MFA_ENROLMENT_CLOSED");
    }

    @Test
    void activeTenantRequiresAnAssignmentAndRevocationTakesEffectOnTheNextRequest() {
        Session session = enrolledSession();
        UUID acme = db.tenantId("acme");
        UUID globex = db.tenantId("globex");

        ApiClient.Response denied = session.client().put("/api/v1/staff/session/active-tenant",
                Map.of("tenantId", acme), Map.of());
        assertThat(denied.status()).isEqualTo(403);
        assertThat(session.client().put("/api/v1/staff/session/active-tenant",
                Map.of("tenantId", UUID.randomUUID()), Map.of()).status()).isEqualTo(403);

        StaffFixtures.assign(session.email(), "acme");
        ApiClient.Response selected = session.client().put("/api/v1/staff/session/active-tenant",
                Map.of("tenantId", acme), Map.of());
        assertThat(selected.status()).isEqualTo(200);
        assertThat(selected.body().get("activeTenant").get("id").asString()).isEqualTo(acme.toString());
        assertThat(selected.body().get("assignedTenants")).hasSize(1);
        // Assignment to acme gives nothing for globex.
        assertThat(session.client().put("/api/v1/staff/session/active-tenant",
                Map.of("tenantId", globex), Map.of()).status()).isEqualTo(403);
        // Still no client API, even with an active tenant.
        assertThat(session.client().get("/api/v1/projects").status()).isEqualTo(403);

        StaffFixtures.revokeAssignment(session.email(), "acme");
        ApiClient.Response after = session.client().get("/api/v1/staff/me");
        assertThat(after.status()).isEqualTo(200);
        assertThat(after.body().get("activeTenant").isNull()).isTrue();
        assertThat(after.body().get("assignedTenants")).isEmpty();
    }

    @Test
    void disablingStaffEndsTheSession() {
        Session session = enrolledSession();
        assertThat(session.client().get("/api/v1/staff/me").status()).isEqualTo(200);
        StaffFixtures.disable(session.email());
        assertThat(session.client().get("/api/v1/staff/me").status()).isEqualTo(401);
        assertThat(login(client(), session.email(), PASSWORD).status()).isEqualTo(401);
    }
}
