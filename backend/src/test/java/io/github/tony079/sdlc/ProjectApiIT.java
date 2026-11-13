package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;

/** Tenant-scoped project API (PRD FR-AUTH-04/06/13, AC-CORE-09 API part). */
class ProjectApiIT extends ApiIntegrationTest {

    private final TestDatabase db = TestDatabase.get();

    @Test
    void createAndListWithinOwnTenant() {
        ApiClient alice = loggedIn("acme", "aaron@acme.test");
        String name = "Alpha " + UUID.randomUUID();
        Response created = alice.post("/api/v1/projects", Map.of("name", name));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("name").asString()).isEqualTo(name);

        Response list = alice.get("/api/v1/projects");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().path("items").valueStream().map(n -> n.path("name").asString())).contains(name);
    }

    @Test
    void anotherTenantCannotListOrReadTheProjectAndTheAttemptIsRecorded() {
        ApiClient alice = loggedIn("acme", "aaron@acme.test");
        String name = "Secret " + UUID.randomUUID();
        String id = alice.post("/api/v1/projects", Map.of("name", name)).body().path("id").asString();

        ApiClient gina = loggedIn("globex", "gina@globex.test");
        assertThat(gina.get("/api/v1/projects").body().path("items").valueStream()
                .map(n -> n.path("name").asString())).doesNotContain(name);

        Response read = gina.get("/api/v1/projects/" + id);
        assertThat(read.status()).isEqualTo(404);
        assertThat(read.errorCode()).isEqualTo("NOT_FOUND");

        Integer events = db.admin().queryForObject("""
                SELECT count(*) FROM app.platform_security_event
                WHERE type = 'RESOURCE_NOT_FOUND' AND target_ref = ? AND tenant_ref = ?
                """, Integer.class, "PROJECT:" + id, db.tenantId("globex"));
        assertThat(events).isEqualTo(1);
    }

    @Test
    void ownerCanReadOwnProjectById() {
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        String id = aaron.post("/api/v1/projects", Map.of("name", "Readable " + UUID.randomUUID()))
                .body().path("id").asString();
        assertThat(aaron.get("/api/v1/projects/" + id).status()).isEqualTo(200);
    }

    @Test
    void tenantIdInBodyIsRejected() {
        ApiClient alice = loggedIn("acme", "aaron@acme.test");
        String name = "Body " + UUID.randomUUID();
        Response response = alice.post("/api/v1/projects",
                Map.of("name", name, "tenantId", db.tenantId("globex").toString()));
        assertThat(response.status()).isEqualTo(422);
        Integer stored = db.admin().queryForObject("SELECT count(*) FROM app.project WHERE name = ?", Integer.class, name);
        assertThat(stored).isZero();
    }

    @Test
    void tenantHeaderIsIgnoredAndTheSessionTenantIsUsed() {
        ApiClient alice = loggedIn("acme", "aaron@acme.test");
        String name = "Header " + UUID.randomUUID();
        Response response = alice.post("/api/v1/projects", Map.of("name", name),
                Map.of("X-Tenant-Id", db.tenantId("globex").toString()), true);
        assertThat(response.status()).isEqualTo(201);
        UUID storedTenant = db.admin().queryForObject("SELECT tenant_id FROM app.project WHERE name = ?", UUID.class, name);
        assertThat(storedTenant).isEqualTo(db.tenantId("acme"));
    }

    @Test
    void sameNameInSameTenantConflictsButOtherTenantMayReuseIt() {
        String name = "Shared " + UUID.randomUUID();
        ApiClient alice = loggedIn("acme", "aaron@acme.test");
        assertThat(alice.post("/api/v1/projects", Map.of("name", name)).status()).isEqualTo(201);
        Response duplicate = alice.post("/api/v1/projects", Map.of("name", name));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.errorCode()).isEqualTo("PROJECT_NAME_TAKEN");

        ApiClient gary = loggedIn("globex", "gary@globex.test");
        assertThat(gary.post("/api/v1/projects", Map.of("name", name)).status()).isEqualTo(201);
    }

    @Test
    void invalidNameIsRejected() {
        ApiClient alice = loggedIn("acme", "aaron@acme.test");
        assertThat(alice.post("/api/v1/projects", Map.of("name", "   ")).status()).isEqualTo(422);
        assertThat(alice.post("/api/v1/projects", Map.of("name", "x".repeat(201))).status()).isEqualTo(422);
    }

    @Test
    void unauthenticatedAccessIsRefused() {
        ApiClient anonymous = client();
        assertThat(anonymous.get("/api/v1/projects").status()).isEqualTo(401);
        assertThat(anonymous.post("/api/v1/projects", Map.of("name", "x")).status()).isEqualTo(401);
    }

    @Test
    void mutationWithoutCsrfIsRefused() {
        ApiClient alice = loggedIn("acme", "aaron@acme.test");
        Response response = alice.post("/api/v1/projects", Map.of("name", "NoCsrf " + UUID.randomUUID()), Map.of(), false);
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo("CSRF_TOKEN_INVALID");
    }

    /** FR-ORG-04: only an Organisation Admin creates, edits and archives projects; members are refused (403). */
    @Test
    void membersCannotCreateOrChangeProjects() {
        ApiClient member = loggedIn("acme", "alice@acme.test");
        assertThat(member.post("/api/v1/projects", Map.of("name", "Nope " + UUID.randomUUID())).errorCode())
                .isEqualTo("ORG_ADMIN_REQUIRED");
        ApiClient admin = loggedIn("acme", "aaron@acme.test");
        String id = admin.post("/api/v1/projects", Map.of("name", "Managed " + UUID.randomUUID())).body().path("id")
                .asString();
        assertThat(member.put("/api/v1/projects/" + id, Map.of("name", "Renamed")).errorCode())
                .isEqualTo("ORG_ADMIN_REQUIRED");
        assertThat(member.post("/api/v1/projects/" + id + "/archive", Map.of()).errorCode())
                .isEqualTo("ORG_ADMIN_REQUIRED");
        // A project the member does not belong to is invisible to them.
        assertThat(member.get("/api/v1/projects/" + id).status()).isEqualTo(404);
        Response archived = admin.post("/api/v1/projects/" + id + "/archive", Map.of());
        assertThat(archived.body().path("status").asString()).isEqualTo("ARCHIVED");
        assertThat(admin.put("/api/v1/projects/" + id, Map.of("name", "Too late")).errorCode())
                .isEqualTo("PROJECT_ARCHIVED");
    }
}
