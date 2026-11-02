package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;

/** Session authentication, CSRF, enumeration resistance and throttling (PRD FR-AUTH-01/06, TDD 20.2, 22.2). */
class AuthFlowIT extends ApiIntegrationTest {

    @Test
    void loginReturnsIdentityFromTheDatabaseAndOpensASession() {
        ApiClient client = client();
        Response login = client.login("acme", "alice@acme.test", TestDatabase.SEED_PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().path("tenant").path("name").asString()).isEqualTo("Acme Corporation");
        assertThat(login.body().path("roles").get(0).asString()).isEqualTo("CLIENT_USER");

        Response me = client.get("/api/v1/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().path("email").asString()).isEqualTo("alice@acme.test");
    }

    @Test
    void organisationAndEmailAreCaseInsensitive() {
        Response login = client().login("ACME", "Alice@Acme.TEST", TestDatabase.SEED_PASSWORD);
        assertThat(login.status()).isEqualTo(200);
    }

    @Test
    void unauthenticatedRequestsGet401WithErrorEnvelope() {
        Response me = client().get("/api/v1/me");
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.errorCode()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void allCredentialFailuresLookIdentical() {
        Response wrongPassword = client().login("acme", "alice@acme.test", "wrong-password-123");
        Response unknownUser = client().login("acme", "nobody@acme.test", TestDatabase.SEED_PASSWORD);
        Response unknownOrganisation = client().login("no-such-org", "alice@acme.test", TestDatabase.SEED_PASSWORD);
        Response otherTenantsUser = client().login("acme", "gina@globex.test", TestDatabase.SEED_PASSWORD);

        for (Response response : new Response[] {wrongPassword, unknownUser, unknownOrganisation, otherTenantsUser}) {
            assertThat(response.status()).isEqualTo(401);
            assertThat(response.errorCode()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(response.body().path("error").path("message").asString())
                    .isEqualTo(wrongPassword.body().path("error").path("message").asString());
        }
    }

    @Test
    void failedLoginIsRecordedAsSecurityEventWithoutCredentials() {
        int before = countEvents("LOGIN_FAILED");
        client().login("acme", "alice@acme.test", "definitely-wrong-1");
        assertThat(countEvents("LOGIN_FAILED")).isEqualTo(before + 1);
        Integer leaked = TestDatabase.get().admin().queryForObject(
                "SELECT count(*) FROM app.platform_security_event WHERE actor_ref ILIKE '%alice%' OR actor_ref ILIKE '%wrong%'",
                Integer.class);
        assertThat(leaked).isZero();
    }

    @Test
    void loginWithoutCsrfTokenIsRefused() {
        Response response = client().post("/api/v1/auth/login",
                Map.of("organisation", "acme", "email", "alice@acme.test", "password", TestDatabase.SEED_PASSWORD),
                Map.of(), false);
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo("CSRF_TOKEN_INVALID");
    }

    @Test
    void sessionIdChangesOnLogin() {
        ApiClient client = loggedIn("acme", "alice@acme.test");
        String first = client.sessionId().orElseThrow();
        assertThat(client.login("acme", "alice@acme.test", TestDatabase.SEED_PASSWORD).status()).isEqualTo(200);
        assertThat(client.sessionId().orElseThrow()).isNotEqualTo(first);
    }

    @Test
    void logoutEndsTheSession() {
        ApiClient client = loggedIn("acme", "aaron@acme.test");
        assertThat(client.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        assertThat(client.get("/api/v1/me").status()).isEqualTo(401);
    }

    @Test
    void repeatedFailuresForOneAccountAreThrottled() {
        String email = "throttle-" + UUID.randomUUID() + "@acme.test";
        for (int i = 0; i < 5; i++) {
            assertThat(client().login("acme", email, "wrong-password-123").status()).isEqualTo(401);
        }
        Response blocked = client().login("acme", email, "wrong-password-123");
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(blocked.headers()).containsKey("retry-after");
    }

    @Test
    void unknownJsonFieldsAreRejected() {
        Response response = client().post("/api/v1/auth/login", Map.of("organisation", "acme",
                "email", "alice@acme.test", "password", TestDatabase.SEED_PASSWORD, "tenantId", UUID.randomUUID().toString()));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.errorCode()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void securityHeadersArePresent() {
        Response response = client().get("/api/v1/auth/csrf");
        assertThat(response.headers().get("content-security-policy")).anyMatch(v -> v.contains("default-src 'none'"));
        assertThat(response.headers().get("x-frame-options")).contains("DENY");
        assertThat(response.headers().get("x-content-type-options")).contains("nosniff");
    }

    private static int countEvents(String type) {
        return TestDatabase.get().admin().queryForObject(
                "SELECT count(*) FROM app.platform_security_event WHERE type = ?", Integer.class, type);
    }
}
