package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
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

        pushToGitHubIssues(tenant, projectId, plan, eve, aaron, victor);
    }

    /** GD-34 phase 2: the approved plan pushed to GitHub Issues of a verified project repository. */
    private void pushToGitHubIssues(UUID tenant, String projectId, String plan, ApiClient eve, ApiClient aaron,
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
    }
}
