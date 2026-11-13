package io.github.tony079.sdlc.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * GD-08 with the production setting: an Organisation Admin signs in with password and then TOTP, enrolling on first
 * sign-in; nothing else is reachable in between; codes are not replayable; another admin can reset the enrolment;
 * members are not asked for MFA.
 */
class OrganisationAdminMfaIT extends ApiIntegrationTest {

    @DynamicPropertySource
    static void mfaOn(DynamicPropertyRegistry registry) {
        registry.add("sdlc.security.org-admin-mfa-required", () -> "true");
    }

    private final JdbcTemplate admin = TestDatabase.get().admin();

    @Test
    void organisationAdminsEnrolAndVerifyTotpMembersDoNot() {
        UUID tenant = WorkerContext.newTenant("mfa");
        String slug = admin.queryForObject("SELECT slug FROM app.tenant WHERE id = ?", String.class, tenant);
        String hash = admin.queryForObject("SELECT password_hash FROM app.app_user WHERE email = 'alice@acme.test'",
                String.class);
        for (String[] u : new String[][] {{"first@" + slug + ".test", "ORG_ADMIN"}, {"second@" + slug + ".test", "ORG_ADMIN"},
                {"member@" + slug + ".test", "MEMBER"}}) {
            admin.update("""
                    INSERT INTO app.app_user (tenant_id, email, display_name, password_hash, role, status)
                    VALUES (?, ?, 'Test', ?, ?, 'ACTIVE')
                    """, tenant, u[0], hash, u[1]);
        }

        // Members sign in with a password only.
        ApiClient member = client();
        assertThat(member.login(slug, "member@" + slug + ".test", TestDatabase.SEED_PASSWORD).body().path("userId")
                .isMissingNode()).isFalse();

        // First sign-in of an admin: enrolment is required; nothing else is reachable yet.
        ApiClient first = client();
        Response login = first.login(slug, "first@" + slug + ".test", TestDatabase.SEED_PASSWORD);
        assertThat(login.body().path("status").asString()).as(login.status() + " " + login.body()).isEqualTo("MFA_ENROLMENT_REQUIRED");
        assertThat(first.get("/api/v1/me").status()).isEqualTo(403);
        assertThat(first.get("/api/v1/organisation/users").status()).isEqualTo(403);
        assertThat(first.post("/api/v1/auth/mfa/verify", Map.of("code", "000000")).status()).isEqualTo(401);
        Response enrolment = first.post("/api/v1/auth/mfa/enrolment", Map.of());
        assertThat(enrolment.status()).isEqualTo(200);
        assertThat(enrolment.body().path("otpauthUri").asString()).startsWith("otpauth://totp/Garuda%3A")
                .contains("issuer=Garuda");
        byte[] secret = StaffFixtures.fromBase32(enrolment.body().path("secret").asString());
        assertThat(first.post("/api/v1/auth/mfa/enrolment/confirm", Map.of("code", "123456")).status()).isEqualTo(401);
        assertThat(first.post("/api/v1/auth/mfa/enrolment/confirm",
                Map.of("code", StaffFixtures.apiCode(secret, -1))).status()).isEqualTo(200);
        assertThat(first.get("/api/v1/organisation/users").status()).isEqualTo(200);
        assertThat(admin.queryForObject("SELECT mfa_secret_enc FROM app.app_user WHERE email = ?", String.class,
                "first@" + slug + ".test")).doesNotContain(enrolment.body().path("secret").asString());

        // Later sign-ins need a fresh code; the code used for enrolment cannot be replayed.
        ApiClient again = client();
        assertThat(again.login(slug, "first@" + slug + ".test", TestDatabase.SEED_PASSWORD).body().path("status")
                .asString()).isEqualTo("MFA_REQUIRED");
        assertThat(again.post("/api/v1/auth/mfa/verify", Map.of("code", StaffFixtures.apiCode(secret, -1))).status())
                .isEqualTo(401);
        assertThat(again.post("/api/v1/auth/mfa/verify", Map.of("code", StaffFixtures.apiCode(secret, 0))).status())
                .isEqualTo(200);
        assertThat(again.get("/api/v1/me").status()).isEqualTo(200);

        // The second admin enrols, then resets the first admin's enrolment; the first admin enrols again next time.
        ApiClient second = client();
        second.login(slug, "second@" + slug + ".test", TestDatabase.SEED_PASSWORD);
        byte[] secondSecret = StaffFixtures.fromBase32(second.post("/api/v1/auth/mfa/enrolment", Map.of()).body()
                .path("secret").asString());
        assertThat(second.post("/api/v1/auth/mfa/enrolment/confirm",
                Map.of("code", StaffFixtures.apiCode(secondSecret, 0))).status()).isEqualTo(200);
        String firstId = admin.queryForObject("SELECT id::text FROM app.app_user WHERE email = ?", String.class,
                "first@" + slug + ".test");
        assertThat(second.post("/api/v1/organisation/users/" + firstId + "/mfa/reset", Map.of()).status())
                .isEqualTo(204);
        assertThat(client().login(slug, "first@" + slug + ".test", TestDatabase.SEED_PASSWORD).body().path("status")
                .asString()).isEqualTo("MFA_ENROLMENT_REQUIRED");
    }
}
