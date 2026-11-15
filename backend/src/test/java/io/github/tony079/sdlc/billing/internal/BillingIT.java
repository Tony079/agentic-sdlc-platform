package io.github.tony079.sdlc.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.RequirementFixtures;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GD-19: projects start PENDING and are activated (or declined) by two Garuda Admins with an approved billing plan;
 * billing plans are approved by a second Garuda Admin; the monthly report prorates the project fee.
 */
class BillingIT extends ApiIntegrationTest {

    private final JdbcTemplate admin = TestDatabase.get().admin();

    private ApiClient garudaAdmin() {
        var member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("billing"), AnalysisScenario.PASSWORD);
        StaffFixtures.grant(member.email(), StaffCapability.STAFF_ADMIN);
        ApiClient c = client();
        assertThat(c.post("/api/v1/staff/auth/login", Map.of("email", member.email(), "password", AnalysisScenario.PASSWORD))
                .status()).isEqualTo(200);
        assertThat(c.post("/api/v1/staff/auth/mfa/verify", Map.of("code", StaffFixtures.apiCode(member.secret(), 0)))
                .status()).isEqualTo(200);
        return c;
    }

    @Test
    void projectsAreActivatedWithAPlanByTwoGarudaAdminsAndBilledFromActivation() {
        ApiClient a = garudaAdmin();
        ApiClient b = garudaAdmin();
        ApiClient orgAdmin = loggedIn("acme", "aaron@acme.test");
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // A plan: proposed by A, not approvable by A, approved by B.
        Response plan = a.post("/api/v1/staff/billing/plans", Map.of("name", "Growth " + suffix, "currency", "INR",
                "monthlyFee", 30000, "includedBrds", 4, "priceSmall", 5000, "priceMedium", 9000, "priceLarge", 15000));
        assertThat(plan.status()).as(plan.body().toString()).isEqualTo(201);
        String planId = plan.body().path("id").asString();
        assertThat(a.post("/api/v1/staff/billing/plans/" + planId + "/approve", Map.of()).errorCode())
                .isEqualTo("SECOND_APPROVER_REQUIRED");
        assertThat(b.post("/api/v1/staff/billing/plans/" + planId + "/approve", Map.of()).body().path("status").asString())
                .isEqualTo("ACTIVE");

        // A new project starts PENDING: no requirements yet.
        Response project = orgAdmin.post("/api/v1/projects", Map.of("name", "Billed " + suffix));
        assertThat(project.body().path("status").asString()).isEqualTo("PENDING");
        String projectId = project.body().path("id").asString();
        String me = orgAdmin.get("/api/v1/me").body().path("userId").asString();
        Response added = orgAdmin.post("/api/v1/projects/" + projectId + "/members", Map.of("userId", me, "role", "BRD_APPROVER"));
        assertThat(added.status()).as(added.body().toString()).isEqualTo(201);
        assertThat(orgAdmin.post("/api/v1/requirements", RequirementFixtures.minimalDraft(projectId, "NEW_PRODUCT"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()), true).errorCode()).isEqualTo("PROJECT_NOT_ACTIVE");
        assertThat(a.get("/api/v1/staff/project-requests").body().toString()).contains(projectId);

        // Activation: proposed by A with the plan, approved by B.
        String tenantId = admin.queryForObject("SELECT id::text FROM app.tenant WHERE slug = 'acme'", String.class);
        Response proposal = a.post("/api/v1/staff/project-requests", Map.of("tenantId", tenantId, "projectId", projectId,
                "decision", "ACTIVATE", "planId", planId, "note", "Signed order form."));
        assertThat(proposal.status()).as(proposal.body().toString()).isEqualTo(201);
        String decisionId = proposal.body().path("id").asString();
        assertThat(a.post("/api/v1/staff/project-decisions/" + decisionId + "/approve", Map.of()).errorCode())
                .isEqualTo("SECOND_APPROVER_REQUIRED");
        assertThat(b.post("/api/v1/staff/project-decisions/" + decisionId + "/approve", Map.of()).status()).isEqualTo(200);
        Response active = orgAdmin.get("/api/v1/projects/" + projectId);
        assertThat(active.body().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.body().path("planName").asString()).isEqualTo("Growth " + suffix);
        assertThat(orgAdmin.post("/api/v1/requirements", RequirementFixtures.minimalDraft(projectId, "NEW_PRODUCT"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()), true).status()).isEqualTo(201);

        // The report bills the project from today, prorated.
        Response report = a.get("/api/v1/staff/billing/report?month=" + YearMonth.now(java.time.ZoneOffset.UTC));
        assertThat(report.body().path("lines").toString()).contains("Billed " + suffix);
        assertThat(a.download("/api/v1/staff/billing/report.csv?month=" + YearMonth.now(java.time.ZoneOffset.UTC)).status())
                .isEqualTo(200);

        // A declined project is final.
        String declined = orgAdmin.post("/api/v1/projects", Map.of("name", "Declined " + suffix)).body().path("id").asString();
        String declineId = b.post("/api/v1/staff/project-requests", Map.of("tenantId", tenantId, "projectId", declined,
                "decision", "DECLINE", "note", "Duplicate of an existing product.")).body().path("id").asString();
        assertThat(a.post("/api/v1/staff/project-decisions/" + declineId + "/approve", Map.of()).status()).isEqualTo(200);
        assertThat(admin.queryForObject("SELECT status FROM app.project WHERE id = ?::uuid", String.class, declined))
                .isEqualTo("DECLINED");
    }

    @Test
    void organisationAdminsCannotActivateOrBillProjectsThemselves() throws Exception {
        UUID tenant = admin.queryForObject("SELECT id FROM app.tenant WHERE slug = 'acme'", UUID.class);
        UUID aaron = admin.queryForObject("SELECT id FROM app.app_user WHERE email = 'aaron@acme.test'", UUID.class);
        try (Connection c = TestDatabase.get().appConnection(); Statement st = c.createStatement()) {
            st.execute("SET app.tenant_id = '" + tenant + "'");
            st.execute("SET app.actor_id = '" + aaron + "'");
            st.executeUpdate("INSERT INTO app.project (tenant_id, name, created_by, status) VALUES ('" + tenant
                    + "', 'Self-activated " + UUID.randomUUID() + "', '" + aaron + "', 'ACTIVE')");
            assertThat(admin.queryForObject("SELECT count(*) FROM app.project WHERE name LIKE 'Self-activated%' AND status = 'PENDING'",
                    Integer.class)).isPositive();
            assertThat(catchThrowable(() -> st.executeUpdate(
                    "UPDATE app.project SET status = 'ACTIVE' WHERE name LIKE 'Self-activated%'")))
                    .hasMessageContaining("only archive");
        }
    }
}
