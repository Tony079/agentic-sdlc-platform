package io.github.tony079.sdlc.sourcerepo.internal;

import static io.github.tony079.sdlc.support.RequirementFixtures.complete;
import static io.github.tony079.sdlc.support.RequirementFixtures.ifMatch;
import static io.github.tony079.sdlc.support.RequirementFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.outbox.internal.OutboxDispatcher;
import io.github.tony079.sdlc.queue.internal.JdbcJobStore;
import io.github.tony079.sdlc.queue.internal.JobWorker;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.FakeGitHub;
import io.github.tony079.sdlc.support.FakeGitHub.Repo;
import io.github.tony079.sdlc.support.Samples;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R1-M4 end to end against the fake GitHub (TEST ONLY: real GitHub is not verified). Client link (C-18, Q-A), staff
 * registration (C-01, O-06, O-11), REPO_VERIFY, REPO_VALIDATE (SHA pin, T06/T07), T09, snapshot and inventory.
 * Failure paths never reach analysis (no BA job); a validated, indexed repository leads to T11 (R1-M5).
 */
class RepositoryIntegrationIT extends ApiIntegrationTest {

    private static final String PASSWORD = "staff-password-" + UUID.randomUUID();
    private static final AtomicLong GITHUB_IDS = new AtomicLong(50_000_000L + (System.nanoTime() % 1_000_000));

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();
    private final FakeGitHub github = FakeGitHub.get();
    private UUID acme;

    @BeforeEach
    void worker() {
        WorkerContext.get();
        acme = db.tenantId("acme");
    }

    @AfterEach
    void resetFake() {
        github.reset();
    }

    // --- fixtures ------------------------------------------------------------------------------------------------

    private record Scenario(ApiClient alice, String projectId, String requirementId, Response created, String linkId,
                            Repo repo) {
    }

    private static String sha() {
        return UUID.randomUUID().toString().replace("-", "") + "01234567";
    }

    private Repo fakeRepo(boolean installed) {
        String name = "loan-service-" + UUID.randomUUID().toString().substring(0, 8);
        String sha = sha();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pom.xml", """
                <project><dependencies><dependency><groupId>org.postgresql</groupId>
                <artifactId>postgresql</artifactId><version>42.7.3</version></dependency></dependencies></project>
                """.getBytes(StandardCharsets.UTF_8));
        files.put("src/main/java/com/acme/LoanController.java", """
                package com.acme;
                @org.springframework.web.bind.annotation.RestController
                @org.springframework.web.bind.annotation.RequestMapping("/loans")
                class LoanController {
                    @org.springframework.web.bind.annotation.GetMapping("/{id}")
                    String get() { return "x"; }
                }
                """.getBytes(StandardCharsets.UTF_8));
        files.put("docs/notes.md", "# notes".getBytes(StandardCharsets.UTF_8));
        Repo repo = new Repo("acme-bank", name, GITHUB_IDS.incrementAndGet(), "main", sha,
                FakeGitHub.tarball(sha, "acme-bank-" + name + "-" + sha.substring(0, 7), files), installed);
        github.put(repo);
        return repo;
    }

    /** Requirement by alice, link request redeemed by aaron for the fake repository. */
    private Scenario linked(Repo repo) {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        String projectId = project(alice);
        Map<String, Object> body = complete(projectId);
        body.put("repositoryUrl", "https://github.com/" + repo.owner() + "/" + repo.name());
        Response created = alice.post("/api/v1/requirements", body);
        String requirementId = created.body().path("id").asString();
        String token = alice.post("/api/v1/requirements/" + requirementId + "/repository-link-requests", Map.of())
                .body().path("token").asString();
        Response redeemed = loggedIn("acme", "aaron@acme.test").post("/api/v1/repository-link-requests/redeem",
                Map.of("token", token, "repositoryUrl", "https://github.com/" + repo.owner() + "/" + repo.name()));
        assertThat(redeemed.status()).isEqualTo(201);
        return new Scenario(alice, projectId, requirementId, created, redeemed.body().path("linkId").asString(), repo);
    }

    /** A staff member through the real login: password, TOTP, active tenant. */
    private ApiClient staff(Enrolled member, String tenantSlug) {
        ApiClient client = client();
        assertThat(client.post("/api/v1/staff/auth/login", Map.of("email", member.email(), "password", PASSWORD))
                .status()).isEqualTo(200);
        assertThat(client.post("/api/v1/staff/auth/mfa/verify",
                Map.of("code", StaffFixtures.apiCode(member.secret(), 0))).status()).isEqualTo(200);
        if (tenantSlug != null) {
            assertThat(client.put("/api/v1/staff/session/active-tenant", Map.of("tenantId", db.tenantId(tenantSlug)),
                    Map.of()).status()).isEqualTo(200);
        }
        return client;
    }

    private Enrolled registrar(String tenantSlug) {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("registrar"), PASSWORD);
        StaffFixtures.assign(member.email(), tenantSlug);
        StaffFixtures.grant(member.email(), StaffCapability.REPOSITORY_REGISTER);
        return member;
    }

    private Response register(ApiClient staff, Scenario scenario) {
        return staff.post("/api/v1/projects/" + scenario.projectId() + "/repositories", Map.of("linkId", scenario.linkId()));
    }

    private void submit(Scenario scenario) {
        Response latest = scenario.alice().get("/api/v1/requirements/" + scenario.requirementId());
        assertThat(scenario.alice().post("/api/v1/requirements/" + scenario.requirementId() + "/submit", null,
                ifMatch(latest), true).status()).isEqualTo(200);
    }

    /** Runs the worker for this tenant until no event or job is left to do now. */
    private void drive() {
        OutboxDispatcher dispatcher = WorkerContext.bean(OutboxDispatcher.class);
        JobWorker worker = WorkerContext.bean(JobWorker.class);
        for (int round = 0; round < 15; round++) {
            boolean work = false;
            while (dispatcher.dispatchNext(acme) != OutboxDispatcher.Outcome.NONE) {
                work = true;
            }
            for (JdbcJobStore.Claim claim : worker.claim(acme, 20)) {
                worker.process(claim);
                work = true;
            }
            if (!work) {
                return;
            }
        }
    }

    private String state(Scenario scenario) {
        return admin.queryForObject("SELECT projected_state FROM app.workflow_instance WHERE requirement_id = ?::uuid",
                String.class, scenario.requirementId());
    }

    private void assertNoAnalysis(Scenario scenario) {
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.job WHERE aggregate_id = ?::uuid AND job_type = 'BA_ANALYSIS'
                """, Integer.class, scenario.requirementId())).isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.workflow_transition t JOIN app.workflow_instance i ON i.id = t.instance_id
                WHERE i.requirement_id = ?::uuid AND t.to_state = 'ANALYSING'
                """, Integer.class, scenario.requirementId())).isZero();
    }

    // --- tests ---------------------------------------------------------------------------------------------------

    @Test
    void registeredVerifiedValidatedPinnedSnapshottedIndexedAndThenAnalysisStarts() {
        Scenario scenario = linked(fakeRepo(true));
        Enrolled member = registrar("acme");
        ApiClient staff = staff(member, "acme");

        Response pending = staff.get("/api/v1/staff/repository-links");
        assertThat(pending.status()).isEqualTo(200);
        assertThat(pending.body().valueStream().map(n -> n.path("linkId").asString())).contains(scenario.linkId());
        assertThat(pending.body().toString()).doesNotContain(scenario.created().body().path("content").path("title")
                .asString());

        Response registered = register(staff, scenario);
        assertThat(registered.status()).as(registered.body().toString()).isEqualTo(202);
        assertThat(registered.body().path("status").asString()).isEqualTo("PENDING_VERIFICATION");
        String repositoryId = registered.body().path("repositoryId").asString();
        UUID staffId = member.id();
        assertThat(admin.queryForMap("""
                SELECT actor_type, actor_id FROM app.audit_event WHERE action = 'REPOSITORY_REGISTERED' AND resource_id = ?::uuid
                """, repositoryId)).containsEntry("actor_type", "STAFF").containsEntry("actor_id", staffId);

        submit(scenario);
        drive();

        // R1-M4 ends with T06 (validated and pinned); R1-M5 then starts analysis (T11) once the snapshot is indexed.
        assertThat(state(scenario)).isEqualTo("ANALYSING");
        assertThat(admin.queryForList("""
                SELECT t.transition FROM app.workflow_transition t JOIN app.workflow_instance i ON i.id = t.instance_id
                WHERE i.requirement_id = ?::uuid ORDER BY t.seq
                """, String.class, scenario.requirementId())).containsSubsequence("T02_ROUTE_REPOSITORY",
                "T06_REPO_VALIDATED", "T11_START_ANALYSIS");
        assertThat(admin.queryForMap("SELECT status, github_repo_id, default_branch FROM app.repository WHERE id = ?::uuid",
                repositoryId)).containsEntry("status", "VERIFIED").containsEntry("github_repo_id", scenario.repo().id())
                .containsEntry("default_branch", "main");
        assertThat(admin.queryForMap("""
                SELECT status, pinned_sha FROM app.requirement_repository WHERE requirement_id = ?::uuid
                """, scenario.requirementId())).containsEntry("status", "VALIDATED")
                .containsEntry("pinned_sha", scenario.repo().headSha());
        Map<String, Object> snapshot = admin.queryForMap("""
                SELECT s.id, s.status, s.commit_sha, s.file_count, s.storage_key FROM app.repository_snapshot s
                JOIN app.requirement_repository rr ON rr.id = s.requirement_repository_id
                WHERE rr.requirement_id = ?::uuid
                """, scenario.requirementId());
        assertThat(snapshot).containsEntry("status", "INDEXED").containsEntry("commit_sha", scenario.repo().headSha())
                .containsEntry("file_count", 3);
        UUID snapshotId = (UUID) snapshot.get("id");
        assertThat(admin.queryForList("""
                SELECT member_name || ' ' || http_method || ' ' || http_path FROM app.code_symbol
                WHERE snapshot_id = ? AND kind = 'METHOD'
                """, String.class, snapshotId)).containsExactly("get GET /loans/{id}");
        assertThat(admin.queryForList("SELECT artifact_id FROM app.build_dependency WHERE snapshot_id = ?", String.class,
                snapshotId)).containsExactly("postgresql");
        assertThat(admin.queryForObject("""
                SELECT skip_reason FROM app.repository_file WHERE snapshot_id = ? AND path = 'docs/notes.md'
                """, String.class, snapshotId)).isEqualTo("NOT_INDEXED_TYPE");
        // The stored archive is encrypted: the plain tarball bytes are not on disk.
        assertThat((String) snapshot.get("storage_key")).doesNotContain(scenario.repo().name());

        // Validation stage closed; owner sees the status (no content).
        Response status = scenario.alice().get("/api/v1/requirements/" + scenario.requirementId() + "/repository-link");
        assertThat(status.body().path("link").path("status").asString()).isEqualTo("REGISTERED");
        assertThat(status.body().path("validation").path("status").asString()).isEqualTo("VALIDATED");
        assertThat(status.body().path("validation").path("pinnedSha").asString()).isEqualTo(scenario.repo().headSha());
        assertThat(status.body().path("validation").path("snapshotStatus").asString()).isEqualTo("INDEXED");
        assertThat(scenario.alice().get("/api/v1/projects/" + scenario.projectId() + "/repositories").body()
                .path(0).path("status").asString()).isEqualTo("VERIFIED");
        assertThat(staff.get("/api/v1/projects/" + scenario.projectId() + "/repositories").status()).isEqualTo(200);

        // Every installation token requested for this repository was narrowed to it, read-only.
        assertThat(github.tokenRequests()).filteredOn(r -> r.contains(scenario.repo().name())).isNotEmpty()
                .allSatisfy(r -> assertThat(r).contains("\"repositories\":[\"" + scenario.repo().name() + "\"]")
                        .contains("\"contents\":\"read\"").doesNotContain("write"));
        // Nothing token-like was stored in the database.
        assertThat(admin.queryForObject("SELECT count(*) FROM app.job WHERE payload::text LIKE '%ghs_%'", Integer.class))
                .isZero();
    }

    @Test
    void registrationPermissionMatrix() {
        Scenario scenario = linked(fakeRepo(true));
        String path = "/api/v1/projects/" + scenario.projectId() + "/repositories";
        Map<String, String> body = Map.of("linkId", scenario.linkId());

        // Client roles never register.
        assertThat(scenario.alice().post(path, body).status()).isEqualTo(403);
        assertThat(loggedIn("acme", "aaron@acme.test").post(path, body).status()).isEqualTo(403);

        // Staff before MFA.
        Enrolled noMfa = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("nomfa"), PASSWORD);
        ApiClient pendingMfa = client();
        pendingMfa.post("/api/v1/staff/auth/login", Map.of("email", noMfa.email(), "password", PASSWORD));
        assertThat(pendingMfa.post(path, body).status()).isEqualTo(403);

        // Assigned but without REPOSITORY_REGISTER.
        Enrolled assignedOnly = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("assigned"), PASSWORD);
        StaffFixtures.assign(assignedOnly.email(), "acme");
        assertThat(register(staff(assignedOnly, "acme"), scenario).status()).isEqualTo(403);

        // Capability but no active tenant selected.
        Enrolled capableOnly = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("capable"), PASSWORD);
        StaffFixtures.grant(capableOnly.email(), StaffCapability.REPOSITORY_REGISTER);
        assertThat(register(staff(capableOnly, null), scenario).status()).isEqualTo(403);

        // Capability and assignment, but to another tenant: the link is invisible (RLS) -> 404.
        Enrolled globex = registrar("globex");
        assertThat(register(staff(globex, "globex"), scenario).status()).isEqualTo(404);

        // Revoked capability takes effect on the next request.
        Enrolled revoked = registrar("acme");
        ApiClient revokedSession = staff(revoked, "acme");
        StaffFixtures.revokeCapability(revoked.email(), StaffCapability.REPOSITORY_REGISTER);
        assertThat(register(revokedSession, scenario).status()).isEqualTo(403);

        // Wrong project -> 404; a valid registration once; then the link is consumed.
        Enrolled ok = registrar("acme");
        ApiClient okSession = staff(ok, "acme");
        String otherProject = project(scenario.alice());
        assertThat(okSession.post("/api/v1/projects/" + otherProject + "/repositories", body).status()).isEqualTo(404);
        assertThat(register(okSession, scenario).status()).isEqualTo(202);
        assertThat(register(okSession, scenario).status()).isEqualTo(404);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.platform_security_event WHERE type = 'STAFF_ACCESS_DENIED'
                """, Integer.class)).isPositive();
    }

    @Test
    void appNotInstalledFailsTheRequirementThroughT09WithoutARetryPath() {
        Scenario scenario = linked(fakeRepo(false));
        register(staff(registrar("acme"), "acme"), scenario);
        submit(scenario);
        drive();

        assertThat(state(scenario)).isEqualTo("FAILED");
        assertNoAnalysis(scenario);
        assertThat(admin.queryForObject("""
                SELECT r.status FROM app.repository r JOIN app.requirement_repository rr ON rr.repository_id = r.id
                WHERE rr.requirement_id = ?::uuid
                """, String.class, scenario.requirementId())).isEqualTo("ACCESS_DENIED");
        assertThat(admin.queryForMap("""
                SELECT status, failure_code FROM app.requirement_repository WHERE requirement_id = ?::uuid
                """, scenario.requirementId())).containsEntry("status", "FAILED").containsEntry("failure_code", "REPO_ACCESS");
        assertThat(admin.queryForObject("""
                SELECT t.transition FROM app.workflow_transition t JOIN app.workflow_instance i ON i.id = t.instance_id
                WHERE i.requirement_id = ?::uuid ORDER BY t.seq DESC LIMIT 1
                """, String.class, scenario.requirementId())).isEqualTo("T09_REPO_VALIDATION_FAILED");
        // The owner may still cancel (T29 from FAILED); nothing can move it back to REPO_VALIDATING (no T30).
        Response latest = scenario.alice().get("/api/v1/requirements/" + scenario.requirementId());
        assertThat(scenario.alice().post("/api/v1/requirements/" + scenario.requirementId() + "/cancel",
                Map.of("reason", "Repository access was not granted"), ifMatch(latest), true).status()).isEqualTo(200);
    }

    @Test
    void anAttachmentNeedingADecisionLeadsToT07() {
        Scenario scenario = linked(fakeRepo(true));
        Response uploaded = scenario.alice().upload("/api/v1/requirements/" + scenario.requirementId() + "/documents",
                "scan.txt", "text/plain", Samples.text("Scanned notes " + UUID.randomUUID()));
        assertThat(uploaded.status()).isEqualTo(201);
        admin.update("""
                UPDATE app.document_version SET state = 'UNSUPPORTED_CONTENT', scan_result = 'CLEAN',
                       scanned_at = now(), scanner = 'test'
                WHERE document_id = ?::uuid
                """, uploaded.body().path("documentId").asString());
        register(staff(registrar("acme"), "acme"), scenario);
        submit(scenario);
        drive();
        assertThat(state(scenario)).isEqualTo("AWAITING_DOCUMENT_DECISION");
        assertNoAnalysis(scenario);
    }

    @Test
    void aGitHubRepositoryAlreadyRegisteredByAnotherTenantIsUnavailableAndRevealsNothing() {
        Repo repo = fakeRepo(true);
        UUID globex = db.tenantId("globex");
        UUID someone = admin.queryForObject("SELECT id FROM app.staff_user LIMIT 1", UUID.class);
        admin.update("""
                INSERT INTO app.repository (tenant_id, github_owner, github_name, repository_url, status, github_repo_id,
                                            default_branch, registered_by, verified_at)
                VALUES (?, 'globex-org', ?, 'https://github.com/globex-org/x', 'VERIFIED', ?, 'main', ?, now())
                """, globex, repo.name(), repo.id(), someone);
        Scenario scenario = linked(repo);
        register(staff(registrar("acme"), "acme"), scenario);
        submit(scenario);
        drive();
        assertThat(admin.queryForMap("""
                SELECT r.status, r.status_reason, r.github_repo_id FROM app.repository r
                JOIN app.requirement_repository rr ON rr.repository_id = r.id WHERE rr.requirement_id = ?::uuid
                """, scenario.requirementId())).containsEntry("status", "UNAVAILABLE")
                .containsEntry("status_reason", "REPOSITORY_UNAVAILABLE").containsEntry("github_repo_id", null);
        assertThat(state(scenario)).isEqualTo("FAILED");
    }

    @Test
    void validationWaitsWithoutUsingRetriesUntilTheRepositoryIsRegistered() {
        Scenario scenario = linked(fakeRepo(true));
        submit(scenario);
        drive();
        assertThat(state(scenario)).isEqualTo("REPO_VALIDATING");
        Map<String, Object> job = admin.queryForMap("""
                SELECT status, retryable_failure_count, deferral_count, last_error_code FROM app.job
                WHERE aggregate_id = ?::uuid AND job_type = 'REPO_VALIDATE'
                """, scenario.requirementId());
        assertThat(job).containsEntry("status", "QUEUED").containsEntry("retryable_failure_count", 0)
                .containsEntry("last_error_code", "REPOSITORY_NOT_REGISTERED");
        assertThat((Integer) job.get("deferral_count")).isPositive();

        register(staff(registrar("acme"), "acme"), scenario);
        drive();
        assertThat(state(scenario)).isEqualTo("ANALYSING");
    }

    @Test
    void workerAndApiLoginsHaveOnlyTheirRepositoryPrivileges() throws Exception {
        try (var app = db.appConnection(); var statement = app.createStatement()) {
            for (String sql : List.of("UPDATE app.repository SET status = 'VERIFIED'",
                    "UPDATE app.requirement_repository SET pinned_sha = repeat('a', 40)",
                    "INSERT INTO app.repository_snapshot (tenant_id, requirement_repository_id, repository_id, commit_sha, status) "
                            + "VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), repeat('a', 40), 'FAILED')",
                    "INSERT INTO app.code_symbol (tenant_id, snapshot_id, file_path, kind, type_name, start_line, end_line) "
                            + "VALUES (gen_random_uuid(), gen_random_uuid(), 'a', 'CLASS', 'A', 1, 1)",
                    "DELETE FROM app.repository")) {
                assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> statement.execute(sql))).as(sql)
                        .hasMessageContaining("permission denied");
            }
        }
        try (var worker = db.workerConnection(); var statement = worker.createStatement()) {
            for (String sql : List.of("INSERT INTO app.repository (tenant_id, github_owner, github_name, repository_url, "
                            + "registered_by) VALUES (gen_random_uuid(), 'a', 'b', 'c', gen_random_uuid())",
                    "UPDATE app.repository SET github_owner = 'x'",
                    "SELECT * FROM app.repository_link_request", "DELETE FROM app.repository_snapshot")) {
                assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> statement.execute(sql))).as(sql)
                        .hasMessageContaining("permission denied");
            }
        }
    }
}
