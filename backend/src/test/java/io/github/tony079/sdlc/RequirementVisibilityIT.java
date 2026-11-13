package io.github.tony079.sdlc;

import static io.github.tony079.sdlc.support.RequirementFixtures.complete;
import static io.github.tony079.sdlc.support.RequirementFixtures.contentOf;
import static io.github.tony079.sdlc.support.RequirementFixtures.ifMatch;
import static io.github.tony079.sdlc.support.RequirementFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;

/**
 * Project-based requirement visibility (Garuda GD-05, GD-06; FR-ACC-01..03): members of a requirement's project see
 * it; only its owner changes it; an Organisation Admin who is not a member, users of other projects and other tenants
 * get 404; a restricted requirement is visible only to its owner and the project's Approvers. Every miss is recorded.
 */
class RequirementVisibilityIT extends ApiIntegrationTest {

    private final TestDatabase db = TestDatabase.get();

    /** A new ACTIVE member of Acme with the seed password (test setup as superuser). */
    private UUID newAcmeMember(String prefix) {
        String hash = db.admin().queryForObject("SELECT password_hash FROM app.app_user WHERE email = 'alice@acme.test'",
                String.class);
        return db.admin().queryForObject("""
                INSERT INTO app.app_user (tenant_id, email, display_name, password_hash, role, status)
                VALUES (?, ?, 'Test member', ?, 'MEMBER', 'ACTIVE') RETURNING id
                """, UUID.class, db.tenantId("acme"), prefix + "-" + UUID.randomUUID() + "@acme.test", hash);
    }

    private String emailOf(UUID user) {
        return db.admin().queryForObject("SELECT email FROM app.app_user WHERE id = ?", String.class, user);
    }

    private UUID userId(String email) {
        return db.admin().queryForObject("SELECT id FROM app.app_user WHERE email = ?", UUID.class, email);
    }

    private long notFoundEvents(String id) {
        return db.admin().queryForObject("""
                SELECT count(*) FROM app.platform_security_event WHERE type = 'RESOURCE_NOT_FOUND' AND target_ref = ?
                """, Long.class, "REQUIREMENT:" + id);
    }

    @Test
    void aProjectApproverSeesTheRequirementButOnlyTheOwnerChangesIt() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response created = alice.post("/api/v1/requirements", complete(project(alice)));
        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asString();

        // Aaron is an Approver of the project (fixture: every Organisation Admin is an Approver).
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        Response seen = aaron.get("/api/v1/requirements/" + id);
        assertThat(seen.status()).isEqualTo(200);
        assertThat(seen.body().path("ownedByMe").asBoolean()).isFalse();
        assertThat(seen.body().path("myRole").asString()).isEqualTo("APPROVER");
        assertThat(aaron.get("/api/v1/requirements/" + id + "/timeline").status()).isEqualTo(200);
        assertThat(aaron.get("/api/v1/requirements").body().path("items").valueStream()
                .map(n -> n.path("id").asString())).contains(id);
        assertThat(aaron.get("/api/v1/requirements?mine=true").body().path("items").valueStream()
                .map(n -> n.path("id").asString())).doesNotContain(id);
        assertThat(aaron.put("/api/v1/requirements/" + id, contentOf(created), ifMatch(created)).errorCode())
                .isEqualTo("OWNER_REQUIRED");
        assertThat(aaron.post("/api/v1/requirements/" + id + "/submit", null, ifMatch(created), true).errorCode())
                .isEqualTo("OWNER_REQUIRED");
        assertThat(aaron.post("/api/v1/requirements/" + id + "/cancel", Map.of("reason", "Approver cancelling it"),
                ifMatch(created), true).errorCode()).isEqualTo("OWNER_REQUIRED");

        Response after = alice.get("/api/v1/requirements/" + id);
        assertThat(after.body().path("state").asString()).isEqualTo("DRAFT");
        assertThat(after.body().path("version").asLong()).isZero();
        assertThat(after.body().path("ownedByMe").asBoolean()).isTrue();
    }

    @Test
    void nonMembersIncludingANonMemberOrganisationAdminGet404() {
        UUID tenant = db.tenantId("acme");
        UUID aliceId = userId("alice@acme.test");
        // A project where only Alice is a member (no Approver memberships for the Organisation Admin).
        UUID project = db.admin().queryForObject(
                "INSERT INTO app.project (tenant_id, name, created_by) VALUES (?, ?, ?) RETURNING id", UUID.class,
                tenant, "Private " + UUID.randomUUID(), userId("aaron@acme.test"));
        io.github.tony079.sdlc.support.RequirementFixtures.addMember(project, aliceId, "REQUESTER");
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response created = alice.post("/api/v1/requirements", complete(project.toString()));
        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asString();

        ApiClient outsider = loggedIn("acme", emailOf(newAcmeMember("outsider")));
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        for (ApiClient other : java.util.List.of(outsider, aaron)) {
            assertThat(other.get("/api/v1/requirements/" + id).status()).isEqualTo(404);
            assertThat(other.get("/api/v1/requirements/" + id + "/timeline").status()).isEqualTo(404);
            assertThat(other.post("/api/v1/requirements/" + id + "/submit", null, ifMatch(created), true).status())
                    .isEqualTo(404);
            assertThat(other.get("/api/v1/requirements").body().path("items").valueStream()
                    .map(n -> n.path("id").asString())).doesNotContain(id);
        }
        // GD-06: the admin sees that the project exists (name, members), not its requirements.
        assertThat(aaron.get("/api/v1/projects/" + project).status()).isEqualTo(200);
        assertThat(outsider.get("/api/v1/projects/" + project).status()).isEqualTo(404);
        // A member without a writing role cannot create requirements in the project.
        assertThat(outsider.post("/api/v1/requirements", complete(project.toString())).status()).isEqualTo(422);
        assertThat(notFoundEvents(id)).isEqualTo(6);
    }

    @Test
    void aRestrictedRequirementIsHiddenFromViewersButNotFromApprovers() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        String projectId = project(alice);
        UUID viewer = newAcmeMember("viewer");
        io.github.tony079.sdlc.support.RequirementFixtures.addMember(UUID.fromString(projectId), viewer, "VIEWER");
        Response created = alice.post("/api/v1/requirements", complete(projectId));
        String id = created.body().path("id").asString();

        ApiClient viewerClient = loggedIn("acme", emailOf(viewer));
        Response asViewer = viewerClient.get("/api/v1/requirements/" + id);
        assertThat(asViewer.status()).isEqualTo(200);
        assertThat(asViewer.body().path("myRole").asString()).isEqualTo("VIEWER");
        // A viewer can neither create requirements nor restrict them.
        assertThat(viewerClient.post("/api/v1/requirements", complete(projectId)).errorCode())
                .isEqualTo("PROJECT_ROLE_REQUIRED");
        assertThat(viewerClient.put("/api/v1/requirements/" + id + "/restricted", Map.of("restricted", true))
                .errorCode()).isEqualTo("OWNER_OR_APPROVER_REQUIRED");

        Response restricted = alice.put("/api/v1/requirements/" + id + "/restricted", Map.of("restricted", true));
        assertThat(restricted.status()).isEqualTo(200);
        assertThat(restricted.body().path("restricted").asBoolean()).isTrue();
        assertThat(viewerClient.get("/api/v1/requirements/" + id).status()).isEqualTo(404);
        assertThat(viewerClient.get("/api/v1/requirements").body().path("items").valueStream()
                .map(n -> n.path("id").asString())).doesNotContain(id);
        // The project's Approver still sees it; the owner can lift the restriction.
        assertThat(loggedIn("acme", "aaron@acme.test").get("/api/v1/requirements/" + id).status()).isEqualTo(200);
        assertThat(alice.put("/api/v1/requirements/" + id + "/restricted", Map.of("restricted", false)).status())
                .isEqualTo(200);
        assertThat(viewerClient.get("/api/v1/requirements/" + id).status()).isEqualTo(200);
        assertThat(db.admin().queryForObject("""
                SELECT count(*) FROM app.audit_event WHERE action = 'REQUIREMENT_RESTRICTION_CHANGED' AND resource_id = ?::uuid
                """, Integer.class, id)).isEqualTo(2);
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
