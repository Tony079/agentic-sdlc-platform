package io.github.tony079.sdlc.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GD-22: with a billing plan, analysis waits for the BRD quote; the requirement's owner accepts it; the plan's monthly
 * allowance applies first, then the size price; Garuda sees BRD charges in the report and AI cost per organisation,
 * project and requirement; Organisation Admins see their own statement.
 */
class BrdQuoteIT extends ApiIntegrationTest {

    private final JdbcTemplate admin = TestDatabase.get().admin();
    private final String month = YearMonth.now(ZoneOffset.UTC).toString();

    private ApiClient garudaAdmin() {
        var member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("quotes"), AnalysisScenario.PASSWORD);
        StaffFixtures.grant(member.email(), StaffCapability.STAFF_ADMIN);
        ApiClient c = client();
        c.post("/api/v1/staff/auth/login", Map.of("email", member.email(), "password", AnalysisScenario.PASSWORD));
        assertThat(c.post("/api/v1/staff/auth/mfa/verify", Map.of("code", StaffFixtures.apiCode(member.secret(), 0)))
                .status()).isEqualTo(200);
        return c;
    }

    private String plan(ApiClient a, ApiClient b, String name, int included) {
        String id = a.post("/api/v1/staff/billing/plans", Map.of("name", name, "currency", "INR", "monthlyFee", 30000,
                "includedBrds", included, "priceSmall", 5000, "priceMedium", 9000, "priceLarge", 15000))
                .body().path("id").asString();
        assertThat(b.post("/api/v1/staff/billing/plans/" + id + "/approve", Map.of()).status()).isEqualTo(200);
        return id;
    }

    private AnalysisScenario scenario(String planId) {
        Enrolled registrar = AnalysisScenario.registrar();
        ApiClient staff = AnalysisScenario.staffSession(client(), registrar, "acme", 0);
        AnalysisScenario s = AnalysisScenario.prepare(this, loggedIn("acme", "alice@acme.test"),
                loggedIn("acme", "aaron@acme.test"), staff, registrar, "Repayments must carry a channel code.");
        admin.update("UPDATE app.project SET plan_id = ?::uuid WHERE id = ?::uuid", planId, s.projectId);
        return s;
    }

    @Test
    void analysisWaitsForTheAcceptedQuoteAndTheAllowanceAppliesFirst() {
        WorkerContext.get();
        ApiClient a = garudaAdmin();
        ApiClient b = garudaAdmin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String withAllowance = plan(a, b, "Quoted " + suffix, 1);
        String noAllowance = plan(a, b, "Overage " + suffix, 0);

        AnalysisScenario s = scenario(withAllowance);
        s.submit();
        s.drive(AnalysisScenario.workerWithout("BA_ANALYSIS"));
        assertThat(s.state()).isEqualTo("AWAITING_DOCUMENTS");

        // One document and a repository: 5 points, a Medium BRD, inside this month's allowance of one.
        Response quote = s.alice.get("/api/v1/requirements/" + s.requirementId + "/brd-quote");
        assertThat(quote.status()).as(quote.body().toString()).isEqualTo(200);
        assertThat(quote.body().path("status").asString()).isEqualTo("PROPOSED");
        assertThat(quote.body().path("size").asString()).isEqualTo("MEDIUM");
        assertThat(quote.body().path("estimated").asBoolean()).isTrue();
        assertThat(quote.body().path("wouldBeIncluded").asBoolean()).isTrue();
        assertThat(quote.body().path("canDecide").asBoolean()).isTrue();
        assertThat(s.alice.post("/api/v1/requirements/" + s.requirementId + "/brd-quote/accept", Map.of())
                .body().path("included").asBoolean()).isTrue();
        assertThat(s.alice.post("/api/v1/requirements/" + s.requirementId + "/brd-quote/accept", Map.of()).errorCode())
                .isEqualTo("QUOTE_ACCEPTED");
        s.drive(AnalysisScenario.workerWithout("BA_ANALYSIS"));
        assertThat(s.state()).isEqualTo("ANALYSING");

        // An accepted quote is final, even for the database owner's application role.
        assertThat(admin.queryForObject("SELECT charge FROM app.brd_quote WHERE requirement_id = ?::uuid",
                java.math.BigDecimal.class, s.requirementId)).isEqualByComparingTo("0");

        // No allowance: the BRD is charged at its size price.
        AnalysisScenario over = scenario(noAllowance);
        over.submit();
        over.drive(AnalysisScenario.workerWithout("BA_ANALYSIS"));
        Response overQuote = over.alice.post("/api/v1/requirements/" + over.requirementId + "/brd-quote/accept", Map.of());
        assertThat(overQuote.body().path("included").asBoolean()).isFalse();
        assertThat(overQuote.body().path("charge").decimalValue()).isEqualByComparingTo("9000");

        // Garuda's report lists both BRDs; the organisation's statement shows its own charges.
        Response report = a.get("/api/v1/staff/billing/report?month=" + month);
        assertThat(report.body().path("brds").toString()).contains(s.requirementId).contains(over.requirementId);
        assertThat(a.download("/api/v1/staff/billing/report.csv?month=" + month).status()).isEqualTo(200);
        ApiClient orgAdmin = loggedIn("acme", "aaron@acme.test");
        Response statement = orgAdmin.get("/api/v1/organisation/billing?month=" + month);
        assertThat(statement.status()).as(statement.body().toString()).isEqualTo(200);
        assertThat(statement.body().path("lines").toString()).contains("\"type\":\"BRD\"");
        assertThat(s.alice.get("/api/v1/organisation/billing?month=" + month).status()).isEqualTo(403);

        // AI cost: provider-reported spend rolls up by organisation, project and requirement.
        admin.update("""
                INSERT INTO app.model_call_record (tenant_id, request_id, requirement_id, provider, model_id, prompt_id,
                    prompt_version, outcome, attempts, usage_source, input_tokens, output_tokens, cost_usd, latency_ms)
                SELECT ?, gen_random_uuid(), ?::uuid, provider, model_id, 'ba.phase-a', 1, 'SUCCEEDED', 1, 'REPORTED',
                       1000, 200, 0.125, 900
                FROM app.model_registry LIMIT 1
                """, s.tenant, s.requirementId);
        String tenantId = s.tenant.toString();
        Response orgs = a.get("/api/v1/staff/billing/ai-costs?month=" + month);
        assertThat(orgs.status()).as(orgs.body().toString()).isEqualTo(200);
        assertThat(orgs.body().path("rows").toString()).contains(tenantId);
        Response projects = a.get("/api/v1/staff/billing/ai-costs?month=" + month + "&tenantId=" + tenantId);
        assertThat(projects.body().path("level").asString()).isEqualTo("PROJECT");
        assertThat(projects.body().path("rows").toString()).contains(s.projectId);
        Response requirements = a.get("/api/v1/staff/billing/ai-costs?month=" + month + "&tenantId=" + tenantId
                + "&projectId=" + s.projectId);
        assertThat(requirements.body().path("rows").get(0).path("requirementId").asString()).isEqualTo(s.requirementId);
        assertThat(requirements.body().path("rows").get(0).path("costUsd").decimalValue()).isGreaterThanOrEqualTo(
                new java.math.BigDecimal("0.125"));
    }
}
