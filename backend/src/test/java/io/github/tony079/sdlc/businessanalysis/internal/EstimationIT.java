package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.modelgateway.internal.FakeModelProvider;
import io.github.tony079.sdlc.outbox.internal.OutboxDispatcher;
import io.github.tony079.sdlc.queue.internal.JdbcJobStore;
import io.github.tony079.sdlc.queue.internal.JobWorker;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.FakeGitHub;
import io.github.tony079.sdlc.support.RequirementFixtures;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;
import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GD-33: an approved design is handed to the project's Estimators; an Estimator starts the estimate with team and
 * rates; the Estimation agent proposes work items; Garuda flags a wide range and an uncovered design item, an
 * Estimator adjusts one and accepts the gap, and a BRD Approver approves (alone, with confirmation). Only Estimators
 * and BRD Approvers see the content; others see the status.
 */
class EstimationIT extends ApiIntegrationTest {

    private static final String DESIGN_ARCH = "architecture-719e05ce4ed1";
    private static final String DESIGN_TEST = "test-plan-bf9ae507e62f";

    private final JdbcTemplate admin = TestDatabase.get().admin();
    private FakeModelProvider fake;

    @BeforeEach
    void setUp() {
        WorkerContext.get();
        fake = WorkerContext.bean(FakeModelProvider.class);
        fake.reset();
        fake.cannedReply("ba.phase-b", """
                {"feasibility": {"verdict": "FEASIBLE", "reasons": ["The scope is clear."]},
                 "items": [{"kind": "RECOMMENDATION", "section": "ACCEPTANCE_CRITERIA",
                            "statement": "The site shows every extracted section.", "evidence": [], "risk": null,
                            "component": null}],
                 "unsupported": [], "clarifications": []}""");
        fake.cannedReply("arch.files", """
                {"approach": "A new web application.", "relevantPaths": []}""");
        fake.cannedReply("arch.design", """
                {"summary": "A Next.js front end with a Spring Boot API and PostgreSQL.",
                 "items": [
                  {"section": "ARCHITECTURE", "kind": "DECISION", "title": "Next.js, Spring Boot and PostgreSQL",
                   "detail": "Next.js 15 front end, Spring Boot 4 API, PostgreSQL 16.", "component": "Portfolio app",
                   "brdRefs": [], "evidence": []},
                  {"section": "TEST_PLAN", "kind": "TASK", "title": "Section coverage test",
                   "detail": "Test that every extracted section is shown.", "component": null, "brdRefs": [],
                   "evidence": []}
                 ], "openQuestions": []}""");
        fake.cannedReply("plan.backlog", """
                {"epics": [{"title": "Platform", "goal": "A running API.", "designRefs": ["%s"]},
                           {"title": "Portfolio", "goal": "Pages for every section.", "designRefs": []}],
                 "stories": [
                  {"epic": "Platform", "title": "As a developer, I want the API skeleton, so that features can be built",
                   "acceptanceCriteria": ["Given a deploy, when /health is called, then it answers 200"],
                   "estimateRefs": ["est-c2fcebd76476"], "brdRefs": ["acceptance-criteria-99f717e05ef9"],
                   "role": "BACKEND", "priority": "MUST", "dependsOn": []},
                  {"epic": "Portfolio", "title": "As a candidate, I want my pages built, so that I can share them",
                   "acceptanceCriteria": ["Every extracted section is shown"], "estimateRefs": ["est-15ac61fe39aa"],
                   "brdRefs": [], "role": "BACKEND", "priority": "MUST",
                   "dependsOn": ["As a developer, I want the API skeleton, so that features can be built"]},
                  {"epic": "Portfolio", "title": "As a candidate, I want a dark theme, so that the site looks modern",
                   "acceptanceCriteria": ["The dark theme can be chosen"], "estimateRefs": [], "brdRefs": [],
                   "role": "FRONTEND", "priority": "SHOULD", "dependsOn": []}
                 ], "risks": ["Template work may grow"]}""".formatted(DESIGN_ARCH));
        fake.cannedReply("est.items", """
                {"items": [
                  {"title": "Set up the API skeleton", "detail": "Spring Boot project, health endpoint, CI.",
                   "role": "BACKEND", "phase": "SETUP", "designRefs": ["%s", "not-a-design-key"],
                   "lowDays": 2, "likelyDays": 3, "highDays": 5, "assumptions": ["CI runner available"],
                   "risk": null, "dependsOn": []},
                  {"title": "Build the portfolio pages", "detail": "Pages for every section.",
                   "role": "QA", "phase": "TEST", "designRefs": ["%s"],
                   "lowDays": 1, "likelyDays": 2, "highDays": 9, "assumptions": [], "risk": "Unclear layouts",
                   "dependsOn": ["Set up the API skeleton"]}
                 ], "assumptions": ["One release"], "excluded": ["Custom domains"]}""".formatted(DESIGN_ARCH, DESIGN_ARCH));
    }

    @AfterEach
    void tearDown() {
        fake.reset();
    }

    private void drive(UUID tenant) {
        OutboxDispatcher dispatcher = WorkerContext.bean(OutboxDispatcher.class);
        JobWorker worker = WorkerContext.bean(JobWorker.class);
        for (int round = 0; round < 25; round++) {
            boolean work = false;
            while (dispatcher.dispatchNext(tenant) != OutboxDispatcher.Outcome.NONE) {
                work = true;
            }
            for (JdbcJobStore.Claim claim : worker.claim(tenant, 20)) {
                worker.process(claim);
                work = true;
            }
            if (!work) {
                return;
            }
        }
    }

    private ApiClient member(String projectId, String prefix, String role) {
        String email = RequirementFixtures.newMember("acme", prefix);
        UUID user = admin.queryForObject("SELECT id FROM app.app_user WHERE email = ?", UUID.class, email);
        RequirementFixtures.addMember(UUID.fromString(projectId), user, role);
        return loggedIn("acme", email);
    }

    @Test
    void anApprovedDesignIsEstimatedAdjustedAndApprovedWithContentVisibleOnlyToEstimatorsAndApprovers() {
        UUID tenant = TestDatabase.get().tenantId("acme");
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        String projectId = RequirementFixtures.project(alice);
        Map<String, Object> body = RequirementFixtures.complete(projectId);
        body.put("kind", "NEW_PRODUCT");
        body.remove("repositoryUrl");
        String id = alice.post("/api/v1/requirements", body).body().path("id").asString();
        Response latest = alice.get("/api/v1/requirements/" + id);
        assertThat(alice.post("/api/v1/requirements/" + id + "/submit", null, RequirementFixtures.ifMatch(latest), true)
                .status()).isEqualTo(200);
        drive(tenant);
        String brd = "/api/v1/requirements/" + id + "/brd";
        assertThat(alice.post(brd + "/confirm", Map.of()).status()).isEqualTo(200);
        assertThat(aaron.post(brd + "/approve", Map.of()).status()).isEqualTo(200);
        drive(tenant);

        ApiClient tina = member(projectId, "tech", "TECHNICAL_APPROVER");
        ApiClient eve = member(projectId, "estimator", "ESTIMATOR");
        ApiClient victor = member(projectId, "viewer", "VIEWER");
        UUID eveId = admin.queryForObject("SELECT user_id FROM app.project_member WHERE project_id = ?::uuid "
                + "AND 'ESTIMATOR' = ANY (roles)", UUID.class, projectId);
        String design = "/api/v1/requirements/" + id + "/design";
        assertThat(tina.post(design + "/start", Map.of()).status()).isEqualTo(200);
        drive(tenant);
        String estimate = "/api/v1/requirements/" + id + "/estimate";
        assertThat(eve.get(estimate).body().path("review").path("stage").asString()).isEqualTo("DESIGN_NOT_APPROVED");
        assertThat(tina.post(design + "/approve", Map.of("confirmApproveAlone", true)).status()).isEqualTo(200);
        drive(tenant);
        // Handed over to the project's Estimators.
        assertThat(admin.queryForObject("SELECT count(*) FROM app.notification WHERE recipient_id = ? AND kind = "
                + "'ESTIMATE_HANDOVER'", Integer.class, eveId)).isEqualTo(1);

        // Only an Estimator starts it; settings are validated.
        assertThat(eve.get(estimate).body().path("review").path("canStart").asBoolean()).isTrue();
        assertThat(aaron.post(estimate + "/start", Map.of()).errorCode()).isEqualTo("ESTIMATE_DECISION_NOT_ALLOWED");
        assertThat(eve.post(estimate + "/start", Map.of("settings", Map.of("daysPerWeek", 9))).status()).isEqualTo(422);
        assertThat(eve.post(estimate + "/start", Map.of("settings", Map.of("dayRates", Map.of("BACKEND", 5000))))
                .status()).as("rates need a currency").isEqualTo(422);
        Map<String, Object> settings = Map.of("team", Map.of("BACKEND", 2, "QA", 1), "daysPerWeek", 5,
                "contingencyPercent", 10, "currency", "INR", "dayRates", Map.of("BACKEND", 5000, "QA", 4000));
        Response started = eve.post(estimate + "/start", Map.of("settings", settings));
        assertThat(started.status()).as(started.body().toString()).isEqualTo(200);
        drive(tenant);

        // Garuda's checks: an unknown design reference dropped, a wide range flagged, an uncovered design item.
        JsonNode state = eve.get(estimate).body();
        assertThat(state.path("review").path("stage").asString()).isEqualTo("IN_REVIEW");
        JsonNode items = state.path("version").path("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).path("designRefs").size()).isEqualTo(1);
        assertThat(items.get(1).path("flags").toString()).contains("UNCERTAIN");
        assertThat(state.path("review").path("uncoveredDesignItems").toString()).contains(DESIGN_TEST);
        assertThat(state.path("review").path("unacceptedFlags").size()).isEqualTo(1);
        assertThat(aaron.get(estimate).body().path("review").path("canApprove").asBoolean()).isFalse();
        String flaggedKey = items.get(1).path("itemKey").asString();

        // Others see the status only, and no estimate lines in the timeline.
        JsonNode seenByViewer = victor.get(estimate).body();
        assertThat(seenByViewer.path("canSee").asBoolean()).isFalse();
        assertThat(seenByViewer.path("review").path("stage").asString()).isEqualTo("IN_REVIEW");
        assertThat(seenByViewer.path("version").isNull()).isTrue();
        assertThat(victor.get("/api/v1/requirements/" + id + "/activity").body().valueStream()
                .noneMatch(l -> "ESTIMATE".equals(l.path("stage").asString()))).isTrue();
        assertThat(eve.get("/api/v1/requirements/" + id + "/activity").body().valueStream()
                .anyMatch(l -> l.path("message").asString().startsWith("Estimate version 1 ready for review"))).isTrue();

        // The Estimator adjusts the wide item (with a reason) and accepts the uncovered design item.
        assertThat(eve.post(estimate + "/items/" + flaggedKey + "/adjust", Map.of("low", 2, "likely", 3, "high", 4,
                "reason", "short")).status()).isEqualTo(422);
        assertThat(aaron.post(estimate + "/items/" + flaggedKey + "/adjust", Map.of("low", 2, "likely", 3, "high", 4,
                "reason", "Layouts were agreed with the client.")).errorCode()).isEqualTo("ESTIMATE_DECISION_NOT_ALLOWED");
        assertThat(eve.post(estimate + "/items/" + flaggedKey + "/adjust", Map.of("low", 2, "likely", 3, "high", 4,
                "reason", "Layouts were agreed with the client.")).status()).isEqualTo(200);
        assertThat(eve.post(estimate + "/gaps/" + DESIGN_TEST + "/accept", Map.of()).status()).isEqualTo(200);
        state = aaron.get(estimate).body();
        assertThat(state.path("review").path("canApprove").asBoolean()).isTrue();
        JsonNode adjusted = state.path("version").path("items").get(1);
        assertThat(adjusted.path("likely").asDouble()).isEqualTo(3.0);
        assertThat(adjusted.path("proposedHigh").asDouble()).isEqualTo(9.0);

        // Garuda's arithmetic: PERT per item, contingency, cost from rates.
        JsonNode summary = state.path("version").path("summary");
        double expected = (2 + 4 * 3 + 5) / 6.0 + (2 + 4 * 3 + 4) / 6.0;
        assertThat(summary.path("effort").path("p50").asDouble()).isCloseTo(expected, within(0.001));
        assertThat(summary.path("effortWithContingency").path("p50").asDouble()).isCloseTo(expected * 1.1, within(0.001));
        assertThat(summary.path("currency").asString()).isEqualTo("INR");
        assertThat(summary.path("costP50").asDouble()).isCloseTo(5000 * (19 / 6.0) * 1.1 + 4000 * 3 * 1.1, within(1.0));

        // A BRD Approver approves, alone only with confirmation, bound to what was shown.
        String reviewHash = state.path("version").path("reviewHash").asString();
        assertThat(eve.post(estimate + "/approve", Map.of("confirmApproveAlone", true)).errorCode())
                .isEqualTo("ESTIMATE_DECISION_NOT_ALLOWED");
        assertThat(aaron.post(estimate + "/approve", Map.of("expectedReviewHash", reviewHash)).errorCode())
                .isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(aaron.post(estimate + "/approve", Map.of("expectedReviewHash", "0".repeat(64),
                "confirmApproveAlone", true)).errorCode()).isEqualTo("VERSION_CHANGED");
        Response approved = aaron.post(estimate + "/approve", Map.of("expectedReviewHash", reviewHash,
                "confirmApproveAlone", true));
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        assertThat(approved.body().path("review").path("stage").asString()).isEqualTo("APPROVED");

        // Exports for insiders only; the spreadsheet never lets a cell start a formula.
        var csv = eve.download(estimate + "/export?format=csv");
        assertThat(csv.status()).isEqualTo(200);
        String text = new String(csv.body(), StandardCharsets.UTF_8);
        assertThat(text).contains("\"Set up the API skeleton\"").contains("Yes");
        var pdf = aaron.download(estimate + "/export?format=pdf");
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(victor.download(estimate + "/export?format=pdf").status()).isEqualTo(404);
        // The database refuses estimate content to a member who is neither Estimator nor BRD Approver.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.update(
                "UPDATE app.estimate_item SET title = 'x' WHERE project_id = ?::uuid", projectId))
                .hasMessageContaining("immutable");


        // GD-34: the approved estimate is handed over for the delivery plan.
        drive(tenant);
        assertThat(admin.queryForObject("SELECT count(*) FROM app.notification WHERE recipient_id = ? AND kind = "
                + "'PLAN_HANDOVER'", Integer.class, eveId)).isEqualTo(1);
        String plan = "/api/v1/requirements/" + id + "/plan";
        assertThat(victor.get(plan).body().path("canSee").asBoolean()).isFalse();
        assertThat(aaron.post(plan + "/start", Map.of()).errorCode()).isEqualTo("PLAN_DECISION_NOT_ALLOWED");
        assertThat(eve.post(plan + "/start", Map.of("settings", Map.of("sprintWeeks", 6, "startDate", "2026-12-07")))
                .status()).isEqualTo(422);
        Response planned = eve.post(plan + "/start", Map.of("settings", Map.of("sprintWeeks", 1,
                "startDate", "2026-12-07", "daysPerWeek", 5, "team", Map.of("BACKEND", 1, "FRONTEND", 1))));
        assertThat(planned.status()).as(planned.body().toString()).isEqualTo(200);
        drive(tenant);

        // Effort from the approved estimate (adjustment included), sprints from capacity and dependencies.
        JsonNode p = eve.get(plan).body();
        assertThat(p.path("review").path("stage").asString()).isEqualTo("IN_REVIEW");
        JsonNode stories = p.path("version").path("stories");
        assertThat(stories.size()).isEqualTo(3);
        JsonNode skeleton = stories.valueStream().filter(x -> x.path("title").asString().contains("skeleton"))
                .findFirst().orElseThrow();
        JsonNode pages = stories.valueStream().filter(x -> x.path("title").asString().contains("pages built"))
                .findFirst().orElseThrow();
        JsonNode theme = stories.valueStream().filter(x -> x.path("title").asString().contains("dark theme"))
                .findFirst().orElseThrow();
        assertThat(skeleton.path("effortDays").asDouble()).isCloseTo(19 / 6.0, within(0.01));
        assertThat(pages.path("effortDays").asDouble()).isCloseTo(3.0, within(0.01));
        assertThat(skeleton.path("sprint").asInt()).isEqualTo(1);
        assertThat(pages.path("sprint").asInt()).as("one backend person, 5 days a sprint").isEqualTo(2);
        assertThat(p.path("version").path("sprints").get(1).path("start").asString()).isEqualTo("2026-12-14");
        assertThat(p.path("version").path("totalEffortDays").asDouble())
                .isCloseTo(p.path("version").path("estimateEffortDays").asDouble(), within(0.02));
        assertThat(theme.path("flags").toString()).contains("NO_ESTIMATE");
        assertThat(p.path("review").path("unacceptedFlags").size()).isEqualTo(1);
        assertThat(p.path("review").path("openGaps").size()).isZero();
        assertThat(aaron.get(plan).body().path("review").path("canApprove").asBoolean()).isFalse();
        assertThat(victor.get(plan).body().path("canSee").asBoolean()).as("drafts stay private").isFalse();

        // The Estimator edits the flagged story (with a reason); a BRD Approver approves.
        String themeKey = theme.path("storyKey").asString();
        assertThat(eve.post(plan + "/stories/" + themeKey + "/edit", Map.of("changes", Map.of("priority", "COULD",
                "sprint", 3), "reason", "Nice to have after launch.")).status()).isEqualTo(200);
        p = aaron.get(plan).body();
        assertThat(p.path("review").path("canApprove").asBoolean()).isTrue();
        JsonNode edited = p.path("version").path("stories").valueStream()
                .filter(x -> x.path("storyKey").asString().equals(themeKey)).findFirst().orElseThrow();
        assertThat(edited.path("sprint").asInt()).isEqualTo(3);
        assertThat(edited.path("priority").asString()).isEqualTo("COULD");

        // New sprint settings: a new version with the same stories (the edit kept), sprints laid out again, no model call.
        Map<String, Object> sameSettings = Map.of("sprintWeeks", 1, "startDate", "2026-12-07", "daysPerWeek", 5,
                "team", Map.of("BACKEND", 1, "FRONTEND", 1));
        assertThat(eve.post(plan + "/settings", Map.of("settings", sameSettings)).errorCode())
                .isEqualTo("PLAN_SETTINGS_UNCHANGED");
        Map<String, Object> twoWeeks = Map.of("sprintWeeks", 2, "startDate", "2026-12-07", "daysPerWeek", 5,
                "team", Map.of("BACKEND", 1, "FRONTEND", 1));
        assertThat(aaron.post(plan + "/settings", Map.of("settings", twoWeeks)).errorCode())
                .isEqualTo("PLAN_DECISION_NOT_ALLOWED");
        int modelCalls = fake.calls().size();
        Response resettled = eve.post(plan + "/settings", Map.of("settings", twoWeeks));
        assertThat(resettled.status()).as(resettled.body().toString()).isEqualTo(200);
        drive(tenant);
        assertThat(fake.calls()).as("no model call for new sprint settings").hasSize(modelCalls);
        p = eve.get(plan).body();
        assertThat(p.path("review").path("stage").asString()).isEqualTo("IN_REVIEW");
        assertThat(p.path("version").path("versionNo").asInt()).isEqualTo(2);
        assertThat(p.path("version").path("settings").path("sprintWeeks").asInt()).isEqualTo(2);
        assertThat(p.path("version").path("stories").size()).isEqualTo(3);
        JsonNode kept = p.path("version").path("stories").valueStream()
                .filter(x -> x.path("storyKey").asString().equals(themeKey)).findFirst().orElseThrow();
        assertThat(kept.path("priority").asString()).as("the edit is kept").isEqualTo("COULD");
        assertThat(p.path("version").path("stories").valueStream()
                .filter(x -> x.path("title").asString().contains("pages built")).findFirst().orElseThrow()
                .path("sprint").asInt()).as("two-week sprints hold both backend stories").isEqualTo(1);
        // Every story still flagged the same way is kept as proposed in one go.
        assertThat(p.path("review").path("unacceptedFlags").size()).isEqualTo(1);
        assertThat(eve.post(plan + "/flags/accept-all", Map.of("flag", "NOPE")).status()).isEqualTo(422);
        assertThat(eve.post(plan + "/flags/accept-all", Map.of("flag", "NO_ESTIMATE")).status()).isEqualTo(200);
        p = aaron.get(plan).body();
        assertThat(p.path("review").path("unacceptedFlags").size()).isZero();
        assertThat(p.path("review").path("canApprove").asBoolean()).isTrue();
        Response planApproved = aaron.post(plan + "/approve", Map.of("expectedReviewHash",
                p.path("version").path("reviewHash").asString(), "confirmApproveAlone", true));
        assertThat(planApproved.status()).as(planApproved.body().toString()).isEqualTo(200);
        // The requirements list shows how far design, estimate and plan have got.
        JsonNode listed = alice.get("/api/v1/requirements").body().path("items").valueStream()
                .filter(r -> r.path("id").asString().equals(id)).findFirst().orElseThrow();
        assertThat(listed.path("designStatus").asString()).isEqualTo("APPROVED");
        assertThat(listed.path("estimateStatus").asString()).isEqualTo("APPROVED");
        assertThat(listed.path("planStatus").asString()).isEqualTo("APPROVED");

        // The approved plan is the project's: a Viewer sees the stories (not the estimate's work items) and exports.
        JsonNode seen = victor.get(plan).body();
        assertThat(seen.path("canSee").asBoolean()).isTrue();
        assertThat(seen.path("version").path("stories").size()).isEqualTo(3);
        assertThat(seen.path("version").path("stories").get(0).path("estimateRefs").size()).isZero();
        assertThat(seen.path("version").path("estimateEffortDays").isNull()).isTrue();
        var planCsv = victor.download(plan + "/export?format=csv");
        assertThat(planCsv.status()).isEqualTo(200);
        assertThat(new String(planCsv.body(), StandardCharsets.UTF_8)).contains("2026-12-07").contains("Platform");
        assertThat(aaron.download(plan + "/export?format=pdf").status()).isEqualTo(200);

        Pushed pushed = pushToGitHubIssues(tenant, projectId, plan, eve, aaron, victor);
        agentDelivery(tenant, estimate, plan, eve, aaron);
        development(tenant, id, plan, alice, eve, aaron, tina, victor, pushed);
    }

    record Pushed(UUID repositoryId, String name) {
    }

    /** GD-35: a new estimate in agent delivery mode, and a plan in waves from it. */
    private void agentDelivery(UUID tenant, String estimate, String plan, ApiClient eve, ApiClient aaron) {
        fake.cannedReply("est.items", """
                {"items": [
                  {"title": "Set up the API skeleton", "detail": "Spring Boot project, health endpoint, CI.",
                   "kind": "WORK", "role": "BACKEND", "phase": "SETUP", "designRefs": ["%s"], "agentHours": 6,
                   "reviewLowDays": 0.5, "reviewLikelyDays": 1, "reviewHighDays": 2, "leadDays": null,
                   "assumptions": [], "risk": null, "dependsOn": []},
                  {"title": "Build the portfolio pages", "detail": "Pages for every section, with tests.",
                   "kind": "WORK", "role": "QA", "phase": "TEST", "designRefs": ["%s", "%s"], "agentHours": 8,
                   "reviewLowDays": 1, "reviewLikelyDays": 1.5, "reviewHighDays": 3, "leadDays": null,
                   "assumptions": [], "risk": null, "dependsOn": ["Set up the API skeleton"]},
                  {"title": "Register the OAuth applications", "detail": "Google, LinkedIn and GitHub apps.",
                   "kind": "EXTERNAL", "role": "DEVOPS", "phase": "SETUP", "designRefs": [], "agentHours": 0,
                   "reviewLowDays": 0.5, "reviewLikelyDays": 0.5, "reviewHighDays": 1, "leadDays": 5,
                   "assumptions": [], "risk": null, "dependsOn": []}
                 ], "assumptions": [], "excluded": []}""".formatted(DESIGN_ARCH, DESIGN_ARCH, DESIGN_TEST));
        Map<String, Object> settings = Map.of("deliveryMode", "AGENT", "team", Map.of("BACKEND", 1, "QA", 1),
                "daysPerWeek", 5, "contingencyPercent", 0, "agentSlots", 2, "currency", "USD", "agentCostPerHour", 2);
        assertThat(eve.post(estimate + "/start", Map.of("settings", Map.of("deliveryMode", "ROBOTS"))).status())
                .isEqualTo(422);
        Response started = eve.post(estimate + "/start", Map.of("settings", settings));
        assertThat(started.status()).as(started.body().toString()).isEqualTo(200);
        drive(tenant);
        assertThat(fake.calls().getLast().promptVersion()).as("the agent-delivery prompt").isEqualTo(2);
        JsonNode est = eve.get(estimate).body();
        assertThat(est.path("review").path("stage").asString()).as(est.toString()).isEqualTo("IN_REVIEW");
        JsonNode summary = est.path("version").path("summary");
        assertThat(summary.path("deliveryMode").asString()).isEqualTo("AGENT");
        assertThat(summary.path("agentHours").asDouble()).isEqualTo(14.0);
        assertThat(summary.path("waves").asInt()).isEqualTo(2);
        assertThat(summary.path("external").asInt()).isEqualTo(1);
        assertThat(summary.path("aiCost").asDouble()).isEqualTo(28.0);
        JsonNode oauth = est.path("version").path("items").valueStream()
                .filter(i -> "EXTERNAL".equals(i.path("kind").asString())).findFirst().orElseThrow();
        assertThat(oauth.path("leadDays").asDouble()).isEqualTo(5.0);
        assertThat(oauth.path("agentHours").asDouble()).isZero();
        String hash = aaron.get(estimate).body().path("version").path("reviewHash").asString();
        Response approved = aaron.post(estimate + "/approve", Map.of("expectedReviewHash", hash,
                "confirmApproveAlone", true));
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        drive(tenant);

        // The plan follows the estimate's delivery mode: waves, not sprints.
        JsonNode before = eve.get(plan).body();
        assertThat(before.path("review").path("estimateOutdated").asBoolean()).isTrue();
        assertThat(before.path("defaults").path("deliveryMode").asString()).isEqualTo("AGENT");
        Response planned = eve.post(plan + "/start", Map.of("settings", Map.of("startDate", "2026-11-23",
                "daysPerWeek", 5, "team", Map.of("BACKEND", 1, "QA", 1), "sprintWeeks", 2)));
        assertThat(planned.status()).as(planned.body().toString()).isEqualTo(200);
        drive(tenant);
        JsonNode v = eve.get(plan).body().path("version");
        assertThat(v.path("deliveryMode").asString()).as(v.toString()).isEqualTo("AGENT");
        assertThat(v.path("sprints").size()).isZero();
        assertThat(v.path("waves").size()).isGreaterThanOrEqualTo(2);
        assertThat(v.path("agentHours").asDouble()).isEqualTo(14.0);
        JsonNode skeleton = v.path("stories").valueStream().filter(x -> x.path("title").asString().contains("skeleton"))
                .findFirst().orElseThrow();
        JsonNode pages = v.path("stories").valueStream().filter(x -> x.path("title").asString().contains("pages built"))
                .findFirst().orElseThrow();
        assertThat(skeleton.path("agentHours").asDouble()).isEqualTo(6.0);
        assertThat(skeleton.path("sprint").asInt()).as("wave").isEqualTo(1);
        assertThat(pages.path("sprint").asInt()).as("after the skeleton").isEqualTo(2);
        assertThat(v.path("waves").get(0).path("start").asString()).isEqualTo("2026-11-23");
        // The outside work the Planning agent left out is a gap for an Estimator to decide.
        assertThat(eve.get(plan).body().path("review").path("openGaps").toString()).contains("est-");
    }

    /** GD-34 phase 2: the approved plan pushed to GitHub Issues of a verified project repository. */
    private Pushed pushToGitHubIssues(UUID tenant, String projectId, String plan, ApiClient eve, ApiClient aaron,
                                      ApiClient victor) {
        FakeGitHub github = FakeGitHub.get();
        github.reset();
        String name = "portfolio-" + UUID.randomUUID().toString().substring(0, 8);
        long githubId = 900_000_000L + (long) (Math.random() * 1_000_000);
        github.put(new FakeGitHub.Repo("acme-org", name, githubId, "main", "a".repeat(40), new byte[0], true));
        assertThat(eve.get(plan).body().path("push").path("canPush").asBoolean())
                .as("no verified repository yet").isFalse();
        assertThat(eve.get(plan).body().path("push").path("unavailableReason").asString()).contains("Issues: Read and write");
        UUID staff = io.github.tony079.sdlc.support.AnalysisScenario.registrar().id();
        UUID repositoryId = admin.queryForObject("""
                INSERT INTO app.repository (tenant_id, github_owner, github_name, repository_url, status, github_repo_id,
                                            default_branch, registered_by, verified_at)
                VALUES (?, 'acme-org', ?, ?, 'VERIFIED', ?, 'main', ?, now()) RETURNING id
                """, UUID.class, tenant, name, "https://github.com/acme-org/" + name, githubId, staff);
        admin.update("INSERT INTO app.project_repository (tenant_id, project_id, repository_id) VALUES (?, ?::uuid, ?)",
                tenant, projectId, repositoryId);

        JsonNode push = eve.get(plan).body().path("push");
        assertThat(push.path("canPush").asBoolean()).isTrue();
        assertThat(push.path("repositories").get(0).path("fullName").asString()).isEqualTo("acme-org/" + name);
        assertThat(victor.get(plan).body().path("push").path("canPush").asBoolean()).isFalse();
        assertThat(victor.get(plan).body().path("push").path("repositories").size()).isZero();
        assertThat(aaron.post(plan + "/push", Map.of("repositoryId", repositoryId.toString())).errorCode())
                .isEqualTo("PLAN_DECISION_NOT_ALLOWED");

        // Before the App is granted Issues: Read and write, the push fails and says why.
        github.issuesPermission(false);
        Response first = eve.post(plan + "/push", Map.of("repositoryId", repositoryId.toString()));
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        assertThat(eve.post(plan + "/push", Map.of("repositoryId", repositoryId.toString())).errorCode())
                .as("one push at a time").isEqualTo("PLAN_PUSH_RUNNING");
        drive(tenant);
        JsonNode failed = eve.get(plan).body().path("push").path("pushes").get(0);
        assertThat(failed.path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("failureCode").asString()).isEqualTo("GITHUB_ISSUES_PERMISSION");
        assertThat(failed.path("message").asString()).contains("Issues: Read and write");
        assertThat(github.issues("acme-org", name)).isEmpty();
        // The token asked for issues and metadata only: never the repository's contents.
        assertThat(github.tokenRequests().getLast()).contains("\"issues\":\"write\"").doesNotContain("contents");

        // Granted: stories become issues with checklists, sprints become milestones, labels for epic/role/priority.
        github.issuesPermission(true);
        assertThat(eve.post(plan + "/push", Map.of("repositoryId", repositoryId.toString())).status()).isEqualTo(200);
        drive(tenant);
        JsonNode done = eve.get(plan).body().path("push");
        assertThat(done.path("pushes").get(0).path("status").asString()).as(done.toString()).isEqualTo("DONE");
        int sprints = eve.get(plan).body().path("version").path("sprints").size();
        assertThat(done.path("pushes").get(0).path("created").asInt()).isEqualTo(3 + sprints);
        assertThat(github.issues("acme-org", name)).hasSize(3);
        assertThat(github.milestones("acme-org", name)).hasSize(sprints);
        assertThat(github.labels("acme-org", name)).containsKeys("garuda", "epic: platform", "priority: must",
                "role: backend");
        assertThat(done.path("issueUrls").size()).isEqualTo(3);
        Map.Entry<Integer, Map<String, Object>> skeletonIssue = github.issues("acme-org", name).entrySet().stream()
                .filter(e -> String.valueOf(e.getValue().get("title")).contains("skeleton")).findFirst().orElseThrow();
        Map<String, Object> pagesIssue = github.issues("acme-org", name).values().stream()
                .filter(i -> String.valueOf(i.get("title")).contains("pages built")).findFirst().orElseThrow();
        assertThat(String.valueOf(skeletonIssue.getValue().get("body")))
                .contains("- [ ] Given a deploy, when /health is called, then it answers 200").contains("**Sprint:** 1");
        assertThat(String.valueOf(pagesIssue.get("body"))).as("dependencies link the pushed issue")
                .contains("- #" + skeletonIssue.getKey() + " As a developer, I want the API skeleton");
        assertThat(pagesIssue.get("milestone")).isNotNull();

        // A developer ticks a criterion on GitHub; pushing again updates the same issues and keeps the tick.
        github.editIssueBody("acme-org", name, skeletonIssue.getKey(),
                String.valueOf(skeletonIssue.getValue().get("body")).replace("- [ ] Given", "- [x] Given"));
        assertThat(eve.post(plan + "/push", Map.of("repositoryId", repositoryId.toString())).status()).isEqualTo(200);
        drive(tenant);
        JsonNode again = eve.get(plan).body().path("push").path("pushes").get(0);
        assertThat(again.path("status").asString()).isEqualTo("DONE");
        assertThat(again.path("created").asInt()).isZero();
        assertThat(again.path("updated").asInt()).isEqualTo(3 + sprints);
        assertThat(github.issues("acme-org", name)).hasSize(3);
        assertThat(String.valueOf(github.issues("acme-org", name).get(skeletonIssue.getKey()).get("body")))
                .contains("- [x] Given a deploy");
        // A Viewer sees the push status and the issue links once the plan is approved.
        assertThat(victor.get(plan).body().path("push").path("issueUrls").size()).isEqualTo(3);
        // What was pushed is never deleted from Garuda's record.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.update("DELETE FROM app.plan_push_item"))
                .hasMessageContaining("not deleted");
        github.reset();
        return new Pushed(repositoryId, name);
    }

    /**
     * GD-36: the approved agent-delivery plan, pushed, starts development on its own. The (fake) model codes each story
     * in the real devbox; Garuda opens a pull request per story on a garuda/ branch; merging wave 1 starts wave 2; when
     * everything is merged the requirement is delivered. A missing GitHub permission fails the story with a reason, and
     * a Technical Approver retries it.
     */
    private void development(UUID tenant, String id, String plan, ApiClient alice, ApiClient eve, ApiClient aaron,
                             ApiClient tina,
                             ApiClient victor, Pushed pushed) {
        FakeGitHub github = FakeGitHub.get();
        github.put(new FakeGitHub.Repo("acme-org", pushed.name(), 900_000_001L + Math.abs(pushed.name().hashCode() % 1000),
                "main", "c".repeat(40), FakeGitHub.tarball("c".repeat(40), "acme-org-site-ccccccc", Map.of(
                        "README.md", "# Portfolio\n".getBytes(StandardCharsets.UTF_8),
                        "app.py", "print('portfolio')\n".getBytes(StandardCharsets.UTF_8))), true));
        // Approve the agent plan (its flag and the outside-work gap decided), then push it.
        JsonNode p = eve.get(plan).body();
        assertThat(eve.post(plan + "/flags/accept-all", Map.of("flag", "NO_ESTIMATE")).status()).isIn(200, 422);
        for (JsonNode gap : p.path("version").path("gaps")) {
            eve.post(plan + "/gaps/" + gap.path("key").asString() + "/accept", Map.of());
        }
        p = aaron.get(plan).body();
        for (JsonNode story : p.path("version").path("stories")) {
            if (story.path("flags").size() > 0 && !story.path("accepted").asBoolean()) {
                eve.post(plan + "/flags/" + story.path("storyKey").asString() + "/accept", Map.of());
            }
        }
        p = aaron.get(plan).body();
        Response approved = aaron.post(plan + "/approve", Map.of("expectedReviewHash",
                p.path("version").path("reviewHash").asString(), "confirmApproveAlone", true));
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);

        // The project's QC environment maps the repository to releases/qc. The new-product requirement names QC as the
        // environment its code is delivered into (never analysed): work starts there and pull requests go into it.
        UUID requirementUuid = UUID.fromString(id);
        admin.update("""
                DO $$ DECLARE e uuid; BEGIN
                  SET LOCAL session_replication_role = replica;
                  INSERT INTO app.environment (tenant_id, project_id, name, created_by)
                    SELECT tenant_id, project_id, 'QC for development', created_by FROM app.requirement
                    WHERE id = '%s' RETURNING id INTO e;
                  INSERT INTO app.environment_branch (tenant_id, project_id, environment_id, repository_id, branch, added_by,
                                                      status, head_sha, verified_at)
                    SELECT tenant_id, project_id, e, '%s', 'releases/qc', created_by, 'VERIFIED', repeat('c', 40), now()
                    FROM app.requirement WHERE id = '%s';
                END $$""".formatted(requirementUuid, pushed.repositoryId(), requirementUuid));
        UUID qc = admin.queryForObject("SELECT id FROM app.environment WHERE name = 'QC for development' AND project_id = "
                + "(SELECT project_id FROM app.requirement WHERE id = ?::uuid)", UUID.class, id);
        assertThat(eve.put("/api/v1/requirements/" + id + "/delivery-environment", Map.of("environmentId",
                qc.toString())).errorCode()).isEqualTo("OWNER_REQUIRED");
        assertThat(alice.put("/api/v1/requirements/" + id + "/delivery-environment", Map.of("environmentId",
                qc.toString())).status()).isEqualTo(204);
        assertThat(alice.get("/api/v1/requirements/" + id + "/target-environment").body()
                .path("deliveryEnvironmentId").asString()).isEqualTo(qc.toString());
        // Not analysed: the requirement still names no analysed environment.
        assertThat(admin.queryForObject("SELECT target_environment_id FROM app.requirement WHERE id = ?::uuid",
                UUID.class, id)).isNull();
        JsonNode pushOptions = eve.get(plan).body().path("push").path("repositories");
        assertThat(pushOptions.get(0).path("fromRequirement").path("branch").asString()).isEqualTo("releases/qc");
        assertThat(pushOptions.valueStream().filter(r -> r.path("id").asString().equals(
                pushed.repositoryId().toString())).findFirst().orElseThrow().path("branches").valueStream()
                .map(b -> b.path("branch").asString())).contains("releases/qc", "main");
        String dev = "/api/v1/requirements/" + id + "/development";
        JsonNode before = eve.get(dev).body();
        assertThat(before.path("run").isNull()).isTrue();
        // The plan pushed so far was made for a human team: the card says so and offers no start.
        assertThat(before.path("notStarted").asString()).contains("human team");
        assertThat(before.path("canStart").asBoolean()).isFalse();
        assertThat(eve.post(dev + "/start", Map.of()).errorCode()).isEqualTo("DEV_NOT_STARTABLE");
        // Wave 1 holds two stories: the first is refused for a missing permission, then retried.
        github.codePermission(false);
        fake.enqueue(writeAndDone("hello.py", "print('hello')\n"), done(), writeAndDone("theme.css", "body{}\n"),
                done());
        assertThat(eve.post(plan + "/push", Map.of("repositoryId", pushed.repositoryId().toString(),
                "baseBranch", "releases/nope")).errorCode()).isEqualTo("VALIDATION_FAILED");
        // Pushed without choosing a branch: the delivery environment's branch is used.
        assertThat(eve.post(plan + "/push", Map.of("repositoryId", pushed.repositoryId().toString())).status())
                .isEqualTo(200);
        drive(tenant);
        JsonNode state = victor.get(dev).body();
        assertThat(state.path("run").path("status").asString()).as(state.toString()).isEqualTo("RUNNING");
        assertThat(state.path("canControl").asBoolean()).isFalse();
        JsonNode tasks = state.path("run").path("tasks");
        assertThat(tasks.valueStream().filter(t -> t.path("wave").asInt() == 1)
                .allMatch(t -> "FAILED".equals(t.path("status").asString()))).as(tasks.toString()).isTrue();
        assertThat(tasks.get(0).path("reason").asString()).contains("Contents and Pull requests");
        assertThat(victor.post(dev + "/tasks/" + tasks.get(0).path("id").asString() + "/retry", Map.of()).errorCode())
                .isEqualTo("DEV_CONTROL_NOT_ALLOWED");
        // Outside work is marked done on the card (as closing its issue does), by those who control development only.
        // (This plan has no outside work of its own: one is added to the run as Garuda does when development starts.)
        admin.update("""
                INSERT INTO app.dev_task (tenant_id, project_id, requirement_id, dev_run_id, story_key, title, wave,
                                          external, issue_number)
                SELECT tenant_id, project_id, requirement_id, id, 'outside-oauth', 'Register the OAuth apps', 0, true, 99
                FROM app.dev_run WHERE id = ?::uuid""", state.path("run").path("id").asString());
        JsonNode outside = victor.get(dev).body().path("run").path("tasks").valueStream()
                .filter(t -> t.path("external").asBoolean()).findFirst().orElseThrow();
        String done = dev + "/tasks/" + outside.path("id").asString() + "/done";
        assertThat(victor.post(done, Map.of()).errorCode()).isEqualTo("DEV_CONTROL_NOT_ALLOWED");
        assertThat(tina.post(done, Map.of()).body().path("run").path("tasks").valueStream()
                .filter(t -> t.path("id").asString().equals(outside.path("id").asString())).findFirst().orElseThrow()
                .path("status").asString()).isEqualTo("DONE");
        assertThat(tina.post(done, Map.of()).errorCode()).isEqualTo("DEV_TASK_STATE");
        assertThat(tina.post(dev + "/tasks/" + tasks.get(0).path("id").asString() + "/done", Map.of()).errorCode())
                .isEqualTo("DEV_TASK_STATE");

        github.codePermission(true);
        fake.reset();
        fake.enqueue(writeAndDone("hello.py", "print('hello')\n"), done(), writeAndDone("theme.css", "body{}\n"),
                done(), writeAndDone("pages.py", "PAGES = ['about']\n"), working(), idle(), idle());
        // One story is retried by a person; the other is left CODING with no job behind it (as when a job ends
        // without a result): the orchestrator queues it again on its next look.
        boolean first = true;
        for (JsonNode t : tasks) {
            if (t.path("wave").asInt() != 1) {
                continue;
            }
            if (first) {
                admin.update("UPDATE app.dev_task SET status = 'CODING' WHERE id = ?::uuid", t.path("id").asString());
                first = false;
            } else {
                assertThat(tina.post(dev + "/tasks/" + t.path("id").asString() + "/retry", Map.of()).status())
                        .isEqualTo(200);
            }
        }
        drive(tenant);
        state = tina.get(dev).body();
        tasks = state.path("run").path("tasks");
        List<JsonNode> wave1 = tasks.valueStream().filter(t -> t.path("wave").asInt() == 1).toList();
        assertThat(wave1).allMatch(t -> "PR_OPEN".equals(t.path("status").asString()));
        // Each session leaves one line on the timeline: its answer, the tools it used and the files changed.
        assertThat(tina.get("/api/v1/requirements/" + id + "/activity").body().valueStream()
                .map(l -> l.path("message").asString()))
                .anyMatch(m -> m.contains(", session 1: DONE · tools: run_command 1, write_file 1 · 1 file(s) changed"));
        assertThat(wave1.get(0).path("branch").asString()).startsWith("garuda/req-");
        assertThat(github.pulls("acme-org", pushed.name())).hasSize(wave1.size());
        Map<String, Object> pr = github.pulls("acme-org", pushed.name()).values().iterator().next();
        assertThat(String.valueOf(pr.get("body"))).contains("Closes #").contains("Garuda never merges");
        assertThat(pr.get("base")).isEqualTo("releases/qc");
        assertThat(state.path("run").path("baseBranch").asString()).isEqualTo("releases/qc");
        assertThat(state.path("run").path("branchFrom").asString()).isEqualTo("from the QC for development environment");
        // Once development has started, the branch is changed on the Development card, not on the requirement.
        assertThat(alice.put("/api/v1/requirements/" + id + "/delivery-environment", Map.of("environmentId",
                qc.toString())).errorCode()).isEqualTo("DEVELOPMENT_STARTED");
        // The branch can be changed on a running development (to an option only), by those who control it.
        assertThat(victor.post(dev + "/base-branch", Map.of("branch", "main")).errorCode())
                .isEqualTo("DEV_CONTROL_NOT_ALLOWED");
        assertThat(tina.post(dev + "/base-branch", Map.of("branch", "nope")).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(tina.post(dev + "/base-branch", Map.of("branch", "main")).body().path("run").path("baseBranch")
                .asString()).isEqualTo("main");
        assertThat(tina.post(dev + "/base-branch", Map.of("branch", "releases/qc")).status()).isEqualTo(200);
        // Each story has its own log, session by session.
        JsonNode storyLog = tina.get(dev + "/tasks/" + wave1.get(0).path("id").asString() + "/log").body();
        assertThat(storyLog.valueStream().map(l -> l.path("message").asString()))
                .anyMatch(m -> m.contains("session 1: DONE"))
                .anyMatch(m -> m.contains("pull request #"));
        assertThat(storyLog.valueStream().map(l -> l.path("taskId").asString()))
                .containsOnly(wave1.get(0).path("id").asString());
        assertThat(github.committedFiles(String.valueOf(((Map<?, ?>) pr.get("head")).get("sha"))).keySet())
                .containsAnyOf("hello.py", "theme.css");
        // Wave 2 waits until wave 1 is merged in GitHub.
        assertThat(tasks.valueStream().filter(t -> t.path("wave").asInt() == 2)
                .allMatch(t -> "WAITING".equals(t.path("status").asString()))).isTrue();
        github.pulls("acme-org", pushed.name()).keySet().forEach(n -> github.merge("acme-org", pushed.name(), n));
        assertThat(tina.post(dev + "/pause", Map.of()).status()).isEqualTo(200);
        assertThat(tina.post(dev + "/resume", Map.of()).status()).isEqualTo(200);
        drive(tenant);
        tasks = tina.get(dev).body().path("run").path("tasks");
        assertThat(tasks.valueStream().filter(t -> t.path("wave").asInt() == 1)
                .allMatch(t -> "MERGED".equals(t.path("status").asString()))).isTrue();
        // The wave 2 story wrote a file, then answered CONTINUE twice without doing anything: it stops early
        // instead of using all its sessions, and its work so far goes to a draft pull request on its branch.
        List<JsonNode> wave2 = tasks.valueStream().filter(t -> t.path("wave").asInt() == 2).toList();
        assertThat(wave2).as(tasks.toString()).singleElement().satisfies(t -> {
            assertThat(t.path("status").asString()).isEqualTo("FAILED");
            assertThat(t.path("reason").asString()).contains("no progress in two sessions");
            assertThat(t.path("draft").asBoolean()).isTrue();
        });
        int draftNumber = wave2.get(0).path("prNumber").asInt();
        Map<String, Object> draft = github.pulls("acme-org", pushed.name()).get(draftNumber);
        assertThat(draft.get("draft")).isEqualTo(true);
        assertThat(String.valueOf(draft.get("title"))).startsWith("[Not finished]");
        assertThat(String.valueOf(draft.get("body"))).contains("Not finished").contains("Retry").contains("Work log");
        String draftBranch = wave2.get(0).path("branch").asString();
        String draftHead = github.branchHead("acme-org", pushed.name(), draftBranch);
        assertThat(github.committedFiles(draftHead)).containsKey("pages.py");
        // Retry carries on from the branch head with the work log; finishing turns the same pull request ready.
        int downloads = github.tarballShas().size();
        drive(tenant); // let leftover work finish first, so the scripted answer goes to this story's retry
        fake.enqueue(done());
        assertThat(tina.post(dev + "/tasks/" + wave2.get(0).path("id").asString() + "/retry", Map.of()).status())
                .isEqualTo(200);
        drive(tenant);
        assertThat(github.tarballShas().subList(downloads, github.tarballShas().size())).containsExactly(draftHead);
        tasks = tina.get(dev).body().path("run").path("tasks");
        Map<String, Object> ready = github.pulls("acme-org", pushed.name()).get(draftNumber);
        assertThat(ready.get("draft")).as(ready + " / " + tasks).isEqualTo(false);
        assertThat(String.valueOf(ready.get("title"))).doesNotStartWith("[Not finished]");
        assertThat(tasks.valueStream().filter(t -> t.path("wave").asInt() == 2).findFirst().orElseThrow()
                .path("prNumber").asInt()).isEqualTo(draftNumber);
        assertThat(tasks.valueStream().filter(t -> t.path("wave").asInt() == 2)
                .allMatch(t -> "PR_OPEN".equals(t.path("status").asString()))).as(tasks.toString()).isTrue();
        // Wave 2 merged: development is complete.
        github.pulls("acme-org", pushed.name()).forEach((n, x) -> {
            if (!Boolean.TRUE.equals(x.get("merged"))) {
                github.merge("acme-org", pushed.name(), n);
            }
        });
        tina.post(dev + "/pause", Map.of());
        tina.post(dev + "/resume", Map.of());
        drive(tenant);
        assertThat(tina.get(dev).body().path("run").path("status").asString()).isEqualTo("DELIVERED");
        assertThat(github.branches("acme-org", pushed.name())).allMatch(b -> b.startsWith("garuda/"));
        // People can only pause, resume, retry and skip: the database refuses anything else.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.update("DELETE FROM app.dev_task"))
                .hasMessageContaining("not deleted");
        fake.reset();
    }

    private static FakeModelProvider.CallTools writeAndDone(String path, String content) {
        return new FakeModelProvider.CallTools(List.of(
                new io.github.tony079.sdlc.modelgateway.api.ModelProvider.ToolCall("c1", "write_file",
                        Map.of("path", path, "content", content)),
                new io.github.tony079.sdlc.modelgateway.api.ModelProvider.ToolCall("c2", "run_command",
                        Map.of("command", "python3 " + path))));
    }

    private static FakeModelProvider.Reply working() {
        return new FakeModelProvider.Reply("""
                {"status": "CONTINUE", "summary": "Wrote the pages list.", "testsRun": [], "testsPassed": false,
                 "nextSteps": "Add the page renderer."}""");
    }

    private static FakeModelProvider.Reply idle() {
        return new FakeModelProvider.Reply("""
                {"status": "CONTINUE", "summary": "Looked around.", "testsRun": [], "testsPassed": false,
                 "nextSteps": "Write the code."}""");
    }

    private static FakeModelProvider.Reply done() {
        return new FakeModelProvider.Reply("""
                {"status": "DONE", "summary": "Implemented the story with a test.", "testsRun": ["python3 -m pytest"],
                 "testsPassed": true, "nextSteps": null}""");
    }
}
