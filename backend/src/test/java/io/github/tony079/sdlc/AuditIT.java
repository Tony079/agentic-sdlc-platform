package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.RequirementFixtures;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;

/** Audit events are written in the acting tenant, with actor and correlation id, and without content (PRD FR-AUD-01/05/06). */
class AuditIT extends ApiIntegrationTest {

    private final TestDatabase db = TestDatabase.get();

    @Test
    void loginProjectCreationAndLogoutAreAudited() {
        ApiClient gary = loggedIn("globex", "gary@globex.test");
        UUID garyId = UUID.fromString(gary.get("/api/v1/me").body().path("userId").asString());
        String projectId = gary.post("/api/v1/projects", Map.of("name", "Audited " + UUID.randomUUID()))
                .body().path("id").asString();
        assertThat(gary.post("/api/v1/auth/logout", null).status()).isEqualTo(204);

        List<Map<String, Object>> events = db.admin().queryForList("""
                SELECT tenant_id, actor_type, actor_id, action, resource_type, resource_id, result, correlation_id
                FROM app.audit_event WHERE actor_id = ? ORDER BY occurred_at
                """, garyId);
        assertThat(events).extracting(e -> e.get("action")).containsSubsequence("LOGIN", "PROJECT_CREATED", "LOGOUT");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.get("tenant_id")).isEqualTo(db.tenantId("globex"));
            assertThat(e.get("actor_type")).isEqualTo("USER");
            assertThat(e.get("result")).isEqualTo("SUCCESS");
            assertThat(e.get("correlation_id")).isNotNull();
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.get("action")).isEqualTo("PROJECT_CREATED");
            assertThat(e.get("resource_id")).isEqualTo(UUID.fromString(projectId));
        });
    }

    @Test
    void requirementLifecycleIsAuditedWithoutContent() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response created = alice.post("/api/v1/requirements", RequirementFixtures.complete(RequirementFixtures.project(alice)));
        UUID id = UUID.fromString(created.body().path("id").asString());
        Map<String, Object> edit = RequirementFixtures.contentOf(created);
        edit.put("title", "Audited edit");
        Response revised = alice.put("/api/v1/requirements/" + id, edit, RequirementFixtures.ifMatch(created));
        Response submitted = alice.post("/api/v1/requirements/" + id + "/submit", null,
                RequirementFixtures.ifMatch(revised), true);
        alice.post("/api/v1/requirements/" + id + "/cancel", Map.of("reason", "Business priorities changed"),
                RequirementFixtures.ifMatch(submitted), true);

        List<String> actions = db.admin().queryForList(
                "SELECT action FROM app.audit_event WHERE resource_type = 'REQUIREMENT' AND resource_id = ? ORDER BY occurred_at",
                String.class, id);
        assertThat(actions).containsExactly("REQUIREMENT_CREATED", "REQUIREMENT_REVISED", "REQUIREMENT_SUBMITTED",
                "REQUIREMENT_CANCELLED");
        // The audit table has no column that could hold requirement text or the cancellation reason.
        List<String> columns = db.admin().queryForList("""
                SELECT column_name FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'audit_event'
                ORDER BY column_name
                """, String.class);
        assertThat(columns).containsExactly("action", "actor_id", "actor_type", "correlation_id", "id", "occurred_at",
                "resource_id", "resource_type", "result", "tenant_id");
    }

    /**
     * Condition 3 of the Milestone 2 approval: an illegal transition is rejected, the request's transaction rolls
     * back (the version bump is undone), and the ILLEGAL_TRANSITION_ATTEMPTED event still persists.
     */
    @Test
    void illegalTransitionAttemptIsAuditedEvenThoughTheRequestRollsBack() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response created = alice.post("/api/v1/requirements", RequirementFixtures.complete(RequirementFixtures.project(alice)));
        UUID id = UUID.fromString(created.body().path("id").asString());
        Response submitted = alice.post("/api/v1/requirements/" + id + "/submit", null,
                RequirementFixtures.ifMatch(created), true);
        long versionAfterSubmit = submitted.body().path("version").asLong();

        // Second submit with the current version: passes the version check, then the engine refuses SUBMITTED -> SUBMITTED.
        Response illegal = alice.post("/api/v1/requirements/" + id + "/submit", null,
                RequirementFixtures.ifMatch(submitted), true);
        assertThat(illegal.status()).isEqualTo(409);
        assertThat(illegal.errorCode()).isEqualTo("ILLEGAL_TRANSITION");

        // The request's own changes were rolled back...
        Response after = alice.get("/api/v1/requirements/" + id);
        assertThat(after.body().path("version").asLong()).isEqualTo(versionAfterSubmit);
        assertThat(after.body().path("state").asString()).isEqualTo("SUBMITTED");
        assertThat(alice.get("/api/v1/requirements/" + id + "/timeline").body().size()).isEqualTo(2);

        // ...but the refused attempt is on record, attributed to the actor, with the request's correlation id.
        List<Map<String, Object>> attempts = db.admin().queryForList("""
                SELECT result, actor_id, correlation_id FROM app.audit_event
                WHERE action = 'ILLEGAL_TRANSITION_ATTEMPTED' AND resource_id = ?
                """, id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0)).containsEntry("result", "DENIED")
                .containsEntry("correlation_id", illegal.body().path("error").path("correlationId").asString());
        UUID aliceId = UUID.fromString(alice.get("/api/v1/me").body().path("userId").asString());
        assertThat(attempts.get(0).get("actor_id")).isEqualTo(aliceId);
        Integer submittedEvents = db.admin().queryForObject("""
                SELECT count(*) FROM app.audit_event WHERE action = 'REQUIREMENT_SUBMITTED' AND resource_id = ?
                """, Integer.class, id);
        assertThat(submittedEvents).as("the rolled-back second submit wrote no success event").isEqualTo(1);
    }

    @Test
    void auditRowsOfOneTenantAreInvisibleToAnotherTenant() throws Exception {
        loggedIn("acme", "alice@acme.test");
        UUID acme = db.tenantId("acme");
        Integer existing = db.admin().queryForObject("SELECT count(*) FROM app.audit_event WHERE tenant_id = ?",
                Integer.class, acme);
        assertThat(existing).as("acme has audit rows (superuser view)").isPositive();

        try (Connection app = db.appConnection()) {
            app.setAutoCommit(false);
            try (PreparedStatement context = app.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                context.setString(1, db.tenantId("globex").toString());
                context.execute();
            }
            try (PreparedStatement count = app.prepareStatement("SELECT count(*) FROM app.audit_event WHERE tenant_id = ?")) {
                count.setObject(1, acme);
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("acme rows visible to sdlc_app in globex context").isZero();
                }
            }
            app.rollback();
        }
    }
}
