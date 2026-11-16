package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.modelgateway.internal.FakeModelProvider;
import io.github.tony079.sdlc.outbox.internal.OutboxDispatcher;
import io.github.tony079.sdlc.queue.internal.JdbcJobStore;
import io.github.tony079.sdlc.queue.internal.JobWorker;
import io.github.tony079.sdlc.sourcerepo.internal.EnvironmentHeadRefresher;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.FakeGitHub;
import io.github.tony079.sdlc.support.FakeGitHub.Repo;
import io.github.tony079.sdlc.support.RequirementFixtures;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GD-23: a requirement on an existing product is analysed against its project's PROD environment by default, across
 * every repository of the environment at its branch head; evidence names the repository and commit it came from; the
 * environment is fixed once analysis starts; moved branches are shown, never invalidating anything; after release the
 * author re-analyses explicitly against the new heads.
 */
class EnvironmentAnalysisIT extends ApiIntegrationTest {

    private static final String BILLING = "src/main/java/com/acme/Billing.java";
    private static final String BILLING_TEXT = """
            package com.acme;

            public class Billing {
                // invoices are encrypted at rest
            }
            """;

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();
    private FakeModelProvider fake;

    @BeforeEach
    void setUp() {
        WorkerContext.get();
        fake = WorkerContext.bean(FakeModelProvider.class);
        fake.reset();
    }

    @AfterEach
    void tearDown() {
        fake.reset();
    }

    private void drive(UUID tenant) {
        OutboxDispatcher dispatcher = WorkerContext.bean(OutboxDispatcher.class);
        JobWorker worker = WorkerContext.bean(JobWorker.class);
        for (int round = 0; round < 30; round++) {
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

    private static Repo repo(String owner, String name, long id, String sha, Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, text) -> bytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        Repo repo = new Repo(owner, name, id, "main", sha, FakeGitHub.tarball(sha, owner + "-" + name + "-" + sha.substring(0, 7),
                bytes), true);
        FakeGitHub.get().put(repo);
        return repo;
    }

    private static String sha(char c) {
        return String.valueOf(c).repeat(32) + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private Enrolled staff(StaffCapability capability) {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("envbrd"), AnalysisScenario.PASSWORD);
        StaffFixtures.assign(member.email(), "acme");
        StaffFixtures.grant(member.email(), capability);
        return member;
    }

    @Test
    void analysisCoversEveryRepositoryOfTheEnvironmentAndReanalysisIsExplicit() {
        UUID acme = db.tenantId("acme");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String owner = "acme-env" + suffix.substring(0, 4);
        long ids = System.nanoTime() % 1_000_000_000L + 10;
        Repo loans = repo(owner, "loans-" + suffix, ids, sha('a'), Map.of(AnalysisScenario.CONTROLLER,
                AnalysisScenario.CONTROLLER_TEXT, "pom.xml", "<project></project>"));
        Repo billing = repo(owner, "billing-" + suffix, ids + 1, sha('b'), Map.of(BILLING, BILLING_TEXT));
        String loansPrefix = owner + "/" + loans.name() + "/";
        String billingPrefix = owner + "/" + billing.name() + "/";

        // Project with PROD holding both repositories (set up by the Environment Manager, PROD approved by Aaron).
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        String project = aaron.post("/api/v1/projects", Map.of("name", "Env analysis " + suffix)).body().path("id").asString();
        admin.update("UPDATE app.project SET status = 'ACTIVE', activated_at = now() WHERE id = ?::uuid", project);
        String aliceId = admin.queryForObject("SELECT id::text FROM app.app_user WHERE email = 'alice@acme.test'", String.class);
        String aaronId = admin.queryForObject("SELECT id::text FROM app.app_user WHERE email = 'aaron@acme.test'", String.class);
        aaron.post("/api/v1/projects/" + project + "/members", Map.of("userId", aliceId, "role", "REQUIREMENT_AUTHOR"));
        aaron.post("/api/v1/projects/" + project + "/members", Map.of("userId", aaronId, "role", "BRD_APPROVER"));
        aaron.put("/api/v1/projects/" + project + "/environment-manager", Map.of("userId", aliceId));
        String loansRepo = alice.post("/api/v1/projects/" + project + "/connected-repositories",
                Map.of("repository", owner + "/" + loans.name())).body().path("id").asString();
        String billingRepo = alice.post("/api/v1/projects/" + project + "/connected-repositories",
                Map.of("repository", owner + "/" + billing.name())).body().path("id").asString();
        alice.post("/api/v1/projects/" + project + "/environments", Map.of("name", "QC"));
        alice.post("/api/v1/projects/" + project + "/environments", Map.of("name", "PROD"));
        String prod = admin.queryForObject("SELECT id::text FROM app.environment WHERE project_id = ?::uuid AND name = 'PROD'",
                String.class, project);
        String qc = admin.queryForObject("SELECT id::text FROM app.environment WHERE project_id = ?::uuid AND name = 'QC'",
                String.class, project);
        alice.post("/api/v1/environments/" + prod + "/branches", Map.of("repositoryId", loansRepo, "branch", "main"));
        alice.post("/api/v1/environments/" + prod + "/branches", Map.of("repositoryId", billingRepo, "branch", "main"));
        String mark = alice.post("/api/v1/environments/" + prod + "/mark-production", Map.of("reason", "Live traffic."))
                .body().path("requestId").asString();
        aaron.post("/api/v1/environment-requests/" + mark + "/approve", Map.of());
        drive(acme);

        // A requirement without a repository URL and without a chosen environment: PROD is used.
        Map<String, Object> body = new HashMap<>(RequirementFixtures.complete(project));
        body.remove("repositoryUrl");
        Response created = alice.post("/api/v1/requirements", body);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        String requirement = created.body().path("id").asString();
        Response latest = alice.get("/api/v1/requirements/" + requirement);
        Response submitted = alice.post("/api/v1/requirements/" + requirement + "/submit", null,
                RequirementFixtures.ifMatch(latest), true);
        assertThat(submitted.status()).as(submitted.body().toString()).isEqualTo(200);
        assertThat(admin.queryForObject("SELECT target_environment_id::text FROM app.requirement WHERE id = ?::uuid",
                String.class, requirement)).isEqualTo(prod);

        fake.cannedReply("ba.phase-a", """
                {"understanding": "Repayment metadata.", "components": [],
                 "relevantPaths": ["%s", "%s"]}""".formatted(loansPrefix + AnalysisScenario.CONTROLLER, billingPrefix + BILLING));
        fake.cannedReply("ba.phase-b", """
                {"feasibility": {"verdict": "FEASIBLE", "reasons": ["Fits the existing code."]}, "items": [
                  {"kind": "FACT", "section": "FACTS", "statement": "Loans are served by the controller mapped to /loans.",
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 3, "endLine": 4,
                                 "documentVersionId": null, "chunk": null, "quote": "RequestMapping(\\"/loans\\")"}],
                   "risk": null, "component": "Loan API"},
                  {"kind": "FACT", "section": "FACTS", "statement": "Billing encrypts invoices at rest.",
                   "evidence": [{"type": "REPOSITORY", "path": "%2$s", "startLine": 3, "endLine": 4,
                                 "documentVersionId": null, "chunk": null, "quote": "invoices are encrypted at rest"}],
                   "risk": null, "component": null}
                 ], "unsupported": [], "clarifications": []}""".formatted(loansPrefix + AnalysisScenario.CONTROLLER,
                billingPrefix + BILLING));
        drive(acme);
        assertThat(admin.queryForObject("SELECT projected_state FROM app.workflow_instance WHERE requirement_id = ?::uuid",
                String.class, requirement)).isEqualTo("INTERNAL_REVIEW");

        // Both repositories were analysed; each citation is attributed to its own repository and commit.
        assertThat(admin.queryForObject("""
                SELECT jsonb_array_length(sources) FROM app.analysis_input_set WHERE requirement_id = ?::uuid
                """, Integer.class, requirement)).isEqualTo(2);
        assertThat(admin.queryForList("""
                SELECT e.commit_sha || ' ' || e.support_passed FROM app.brd_item_evidence e
                JOIN app.brd_item i ON i.id = e.brd_item_id JOIN app.brd_version v ON v.id = i.brd_version_id
                WHERE v.requirement_id = ?::uuid ORDER BY e.commit_sha
                """, String.class, requirement)).containsExactly(loans.headSha() + " true", billing.headSha() + " true");

        // Analysis has started: the environment is fixed.
        assertThat(alice.put("/api/v1/requirements/" + requirement + "/target-environment", Map.of("environmentId", qc))
                .errorCode()).isEqualTo("ENVIRONMENT_LOCKED");
        Response sources = alice.get("/api/v1/requirements/" + requirement + "/sources");
        assertThat(sources.status()).as(sources.body().toString()).isEqualTo(200);
        assertThat(sources.body().path("environmentName").asString()).isEqualTo("PROD");
        assertThat(sources.body().path("branchesMoved").asBoolean()).isFalse();

        // The loans branch moves; the scheduled refresh records the new head; nothing is invalidated.
        Repo moved = repo(owner, loans.name(), loans.id(), sha('c'), Map.of(AnalysisScenario.CONTROLLER,
                AnalysisScenario.CONTROLLER_TEXT + "// changed\n", "pom.xml", "<project></project>"));
        WorkerContext.bean(EnvironmentHeadRefresher.class).refreshAll();
        drive(acme);
        sources = alice.get("/api/v1/requirements/" + requirement + "/sources");
        assertThat(sources.body().path("branchesMoved").asBoolean()).isTrue();
        assertThat(sources.body().toString()).contains(moved.headSha());
        assertThat(sources.body().path("canReanalyse").asBoolean()).isFalse(); // not released yet
        assertThat(alice.post("/api/v1/requirements/" + requirement + "/reanalyse", Map.of()).errorCode())
                .isEqualTo("REANALYSIS_NOT_AVAILABLE");
        assertThat(admin.queryForObject("SELECT projected_state FROM app.workflow_instance WHERE requirement_id = ?::uuid",
                String.class, requirement)).isEqualTo("INTERNAL_REVIEW");

        // Approved and released (inputs unchanged: the pinned commits did not move).
        ApiClient reviewer = AnalysisScenario.staffSession(client(), staff(StaffCapability.BUSINESS_REVIEWER), "acme", 0);
        Map<String, Object> v1 = admin.queryForMap("""
                SELECT id::text AS id, content_hash FROM app.brd_version WHERE requirement_id = ?::uuid AND status = 'IN_REVIEW'
                """, requirement);
        Response approved = reviewer.post("/api/v1/staff/brd-versions/" + v1.get("id") + "/approve",
                Map.of("expectedContentHash", v1.get("content_hash")));
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        ApiClient manager = AnalysisScenario.staffSession(client(), staff(StaffCapability.RELEASE_APPROVE), "acme", 0);
        var entry = manager.get("/api/v1/staff/releases").body().valueStream()
                .filter(n -> n.path("brdVersionId").asString().equals(v1.get("id"))).findFirst().orElseThrow();
        Response released = manager.post("/api/v1/staff/brd-versions/" + v1.get("id") + "/release", Map.of(
                "expectedContentHash", entry.path("contentHash").asString(),
                "expectedCommitSha", entry.path("commitSha").asString(), "reason", "Gates passed."));
        assertThat(released.status()).as(released.body().toString()).isEqualTo(200);
        drive(acme);
        assertThat(admin.queryForObject("SELECT projected_state FROM app.workflow_instance WHERE requirement_id = ?::uuid",
                String.class, requirement)).isEqualTo("SHARED_WITH_CLIENT");

        // Only the owner re-analyses; the new round pins the moved head and a new BRD version follows.
        assertThat(aaron.post("/api/v1/requirements/" + requirement + "/reanalyse", Map.of()).errorCode())
                .isEqualTo("REANALYSIS_NOT_AVAILABLE");
        Response reanalysed = alice.post("/api/v1/requirements/" + requirement + "/reanalyse", Map.of());
        assertThat(reanalysed.status()).as(reanalysed.body().toString()).isEqualTo(200);
        drive(acme);
        assertThat(admin.queryForObject("SELECT projected_state FROM app.workflow_instance WHERE requirement_id = ?::uuid",
                String.class, requirement)).isEqualTo("INTERNAL_REVIEW");
        assertThat(admin.queryForObject("""
                SELECT src ->> 'commitSha' FROM app.analysis_input_set s, jsonb_array_elements(s.sources) src
                WHERE s.requirement_id = ?::uuid AND src ->> 'repositoryId' = ?
                ORDER BY s.created_at DESC LIMIT 1
                """, String.class, requirement, loansRepo)).isEqualTo(moved.headSha());
        assertThat(alice.get("/api/v1/requirements/" + requirement + "/sources").body().path("branchesMoved").asBoolean())
                .isFalse();
        // The environment PROD was analysed against cannot be removed.
        assertThat(admin.queryForObject("SELECT count(*) FROM app.requirement_repository WHERE requirement_id = ?::uuid",
                Integer.class, requirement)).isEqualTo(4);
    }
}
