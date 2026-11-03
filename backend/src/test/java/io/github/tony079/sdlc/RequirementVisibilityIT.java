package io.github.tony079.sdlc;

import static io.github.tony079.sdlc.support.RequirementFixtures.complete;
import static io.github.tony079.sdlc.support.RequirementFixtures.contentOf;
import static io.github.tony079.sdlc.support.RequirementFixtures.ifMatch;
import static io.github.tony079.sdlc.support.RequirementFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;

/**
 * Owner-only requirement visibility for both client roles (PRD section 4; decision Q3, conflict C-18 open):
 * another user of the same tenant, including a Client Approver, and another tenant all get 404, and every miss
 * is recorded as a security event.
 */
class RequirementVisibilityIT extends ApiIntegrationTest {

    @Test
    void clientApproverCannotSeeOrChangeAClientUsersRequirement() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response created = alice.post("/api/v1/requirements", complete(project(alice)));
        String id = created.body().path("id").asString();

        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        assertThat(aaron.get("/api/v1/requirements/" + id).status()).isEqualTo(404);
        assertThat(aaron.get("/api/v1/requirements/" + id + "/timeline").status()).isEqualTo(404);
        assertThat(aaron.get("/api/v1/requirements/" + id + "/revisions/1").status()).isEqualTo(404);
        assertThat(aaron.put("/api/v1/requirements/" + id, contentOf(created), ifMatch(created)).status()).isEqualTo(404);
        assertThat(aaron.post("/api/v1/requirements/" + id + "/submit", null, ifMatch(created), true).status())
                .isEqualTo(404);
        assertThat(aaron.post("/api/v1/requirements/" + id + "/cancel", Map.of("reason", "Approver cancelling it"),
                ifMatch(created), true).status()).isEqualTo(404);
        assertThat(aaron.get("/api/v1/requirements").body().path("items").valueStream()
                .map(n -> n.path("id").asString())).doesNotContain(id);

        // The owner's requirement is unchanged by those attempts.
        Response after = alice.get("/api/v1/requirements/" + id);
        assertThat(after.body().path("state").asString()).isEqualTo("DRAFT");
        assertThat(after.body().path("version").asLong()).isZero();

        Integer events = TestDatabase.get().admin().queryForObject("""
                SELECT count(*) FROM app.platform_security_event WHERE type = 'RESOURCE_NOT_FOUND' AND target_ref = ?
                """, Integer.class, "REQUIREMENT:" + id);
        assertThat(events).isEqualTo(6);
    }

    @Test
    void anotherTenantCannotSeeTheRequirement() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        String id = alice.post("/api/v1/requirements", complete(project(alice))).body().path("id").asString();

        ApiClient gina = loggedIn("globex", "gina@globex.test");
        Response read = gina.get("/api/v1/requirements/" + id);
        assertThat(read.status()).isEqualTo(404);
        assertThat(read.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(gina.get("/api/v1/requirements").body().path("items").valueStream()
                .map(n -> n.path("id").asString())).doesNotContain(id);
    }

    @Test
    void unauthenticatedAccessIsRefused() {
        assertThat(client().get("/api/v1/requirements").status()).isEqualTo(401);
    }
}
