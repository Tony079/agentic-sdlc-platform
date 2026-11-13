package io.github.tony079.sdlc.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base class: the full application on a random port, connected to the test database as sdlc_app. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ApiIntegrationTest {

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase db = TestDatabase.get();
        registry.add("spring.datasource.url", db::appUrl);
        registry.add("spring.datasource.username", db::appUser);
        registry.add("spring.datasource.password", db::appPassword);
        registry.add("server.servlet.session.cookie.secure", () -> "false");
        registry.add("logging.structured.format.console", () -> "");
        // Per-client throttling is covered by LoginThrottleTest; all API tests share one client address.
        registry.add("sdlc.security.login-throttle.max-failures-per-client", () -> "1000");
        registry.add("sdlc.storage.root", TestStorage.ROOT::toString);
        registry.add("sdlc.storage.encryption-key", () -> TestStorage.KEY);
        registry.add("sdlc.staff.mfa-key", () -> TestDatabase.MFA_KEY);
        // GD-08 is on by default and in production; the shared context signs the seeded admins in many times per
        // second, which TOTP replay protection would (rightly) refuse. OrganisationAdminIT covers MFA with it on.
        registry.add("sdlc.security.org-admin-mfa-required", () -> "false");
    }

    protected ApiClient client() {
        return new ApiClient(port);
    }

    protected ApiClient loggedIn(String organisation, String email) {
        ApiClient client = client();
        ApiClient.Response response = client.login(organisation, email, TestDatabase.SEED_PASSWORD);
        if (response.status() != 200) {
            throw new IllegalStateException("Login failed for " + email + ": " + response.status() + " " + response.body());
        }
        return client;
    }
}
