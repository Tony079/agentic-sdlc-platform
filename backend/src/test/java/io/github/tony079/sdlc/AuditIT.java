package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
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
