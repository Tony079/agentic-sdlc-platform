package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tony079.sdlc.support.ApiIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

/** SEC-M3-01: no generated in-memory user (and so no generated password in the log); HTTP Basic does nothing. */
class NoDefaultUserIT extends ApiIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void noGeneratedDefaultUserExists() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void basicAuthenticationIsIgnored() {
        var response = client().get("/api/v1/me", java.util.Map.of("Authorization",
                "Basic " + java.util.Base64.getEncoder().encodeToString("user:anything".getBytes())));
        assertThat(response.status()).isEqualTo(401);
    }
}
