package io.github.tony079.sdlc.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** O-08 feature-flag changes and acknowledgement; protected security and release-gate flags cannot change. */
class OperationsIT extends ApiIntegrationTest {

    private final JdbcTemplate admin = TestDatabase.get().admin();

    private ApiClient operator(boolean capability) {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("flags"), AnalysisScenario.PASSWORD);
        StaffFixtures.assign(member.email(), "acme");
        if (capability) {
            StaffFixtures.grant(member.email(), StaffCapability.FEATURE_FLAG_ACK);
        }
        return AnalysisScenario.staffSession(client(), member, "acme", 0);
    }

    @Test
    void flagChangesAreRecordedAndAcknowledgedByAnotherOperator() throws Exception {
        ApiClient a = operator(true);
        ApiClient b = operator(true);
        assertThat(operator(false).get("/api/v1/staff/feature-flags").status()).isEqualTo(403);
        assertThat(loggedIn("acme", "alice@acme.test").get("/api/v1/staff/feature-flags").status()).isIn(401, 403);

        assertThat(a.get("/api/v1/staff/feature-flags").status()).isEqualTo(200);
        String current = admin.queryForObject("""
                SELECT value FROM app.feature_flag WHERE name = 'ui.version-comparison' AND environment = 'default'
                """, String.class);
        String next = "on".equals(current) ? "off" : "on";
        assertThat(a.put("/api/v1/staff/feature-flags/ui.version-comparison/default",
                Map.of("value", next, "reason", "short"), Map.of()).status()).isEqualTo(422);
        Response changed = a.put("/api/v1/staff/feature-flags/ui.version-comparison/default",
                Map.of("value", next, "reason", "Comparison hidden during the UI review."), Map.of());
        assertThat(changed.status()).as(changed.body().toString()).isEqualTo(200);
        String changeId = changed.body().path("id").asString();
        assertThat(changed.body().path("previousValue").asString()).isEqualTo(current);
        assertThat(changed.body().path("newValue").asString()).isEqualTo(next);

        // Visible as unacknowledged in the operations overview.
        Response overview = b.get("/api/v1/staff/operations");
        assertThat(overview.status()).isEqualTo(200);
        assertThat(overview.body().path("unacknowledgedFlagChanges").valueStream()
                .map(n -> n.path("id").asString())).contains(changeId);
        // The author cannot acknowledge their own change; another operator can, once.
        assertThat(a.post("/api/v1/staff/feature-flag-changes/" + changeId + "/acknowledge", Map.of()).errorCode())
                .isEqualTo("SELF_ACKNOWLEDGEMENT_FORBIDDEN");
        assertThat(b.post("/api/v1/staff/feature-flag-changes/" + changeId + "/acknowledge", Map.of()).status())
                .isEqualTo(200);
        assertThat(b.post("/api/v1/staff/feature-flag-changes/" + changeId + "/acknowledge", Map.of()).errorCode())
                .isEqualTo("ALREADY_ACKNOWLEDGED");
        assertThat(admin.queryForList("SELECT action FROM app.platform_audit_event WHERE target_ref = ?", String.class,
                changeId)).contains("FEATURE_FLAG_CHANGED", "FEATURE_FLAG_ACKNOWLEDGED");

        // Protected flags (security / release gates) cannot be changed through the API or directly by the API login.
        assertThat(a.put("/api/v1/staff/feature-flags/release.security-scan-gate/default",
                Map.of("value", "off", "reason", "Trying to disable the release gate."), Map.of()).errorCode())
                .isEqualTo("PROTECTED_FLAG");
        try (var c = TestDatabase.get().appConnection(); var st = c.createStatement()) {
            assertThat(catchThrowable(() -> st.executeUpdate("""
                    UPDATE app.feature_flag SET value = 'off' WHERE name = 'release.security-scan-gate'
                    """))).hasMessageContaining("protected");
            assertThat(catchThrowable(() -> st.executeUpdate("UPDATE app.feature_flag SET protected = false")))
                    .hasMessageContaining("permission denied");
        }
    }
}
