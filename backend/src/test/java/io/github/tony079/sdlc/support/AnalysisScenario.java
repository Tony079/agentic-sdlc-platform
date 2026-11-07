package io.github.tony079.sdlc.support;

import static io.github.tony079.sdlc.support.RequirementFixtures.complete;
import static io.github.tony079.sdlc.support.RequirementFixtures.ifMatch;
import static io.github.tony079.sdlc.support.RequirementFixtures.project;

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
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.queue.internal.JdbcJobStore;
import io.github.tony079.sdlc.queue.internal.JobWorker;
import io.github.tony079.sdlc.queue.internal.TestWorkers;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.FakeGitHub.Repo;

/**
 * Shared end-to-end fixture (TEST ONLY): an Acme requirement linked by the approver to a fake GitHub repository,
 * registered by staff through MFA, optionally with an attachment, then driven through the real worker.
 */
public final class AnalysisScenario {

    public static final String PASSWORD = "staff-password-" + UUID.randomUUID();
    public static final String CONTROLLER = "src/main/java/com/acme/LoanController.java";
    public static final String CONTROLLER_TEXT = """
            package com.acme;
            @org.springframework.web.bind.annotation.RestController
            @org.springframework.web.bind.annotation.RequestMapping("/loans")
            class LoanController {
                @org.springframework.web.bind.annotation.GetMapping("/{id}")
                String get() { return "x"; }
            }
            """;
    private static final AtomicLong GITHUB_IDS = new AtomicLong(70_000_000L + (System.nanoTime() % 1_000_000));

    public final ApiClient alice;
    public final ApiClient owner;
    public final String projectId;
    public final String requirementId;
    public final Response created;
    public final Repo repo;
    public final UUID tenant;
    public String documentId;
    public final Enrolled registrar;

    private AnalysisScenario(ApiClient alice, String projectId, String requirementId, Response created, Repo repo,
                             UUID tenant, Enrolled registrar) {
        this.alice = alice;
        this.owner = alice;
        this.projectId = projectId;
        this.requirementId = requirementId;
        this.created = created;
        this.repo = repo;
        this.tenant = tenant;
        this.registrar = registrar;
    }

    public static Repo fakeRepo() {
        String name = "loan-service-" + UUID.randomUUID().toString().substring(0, 8);
        String sha = UUID.randomUUID().toString().replace("-", "") + "01234567";
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pom.xml", ("<project><dependencies><dependency><groupId>org.postgresql</groupId><artifactId>postgresql"
                + "</artifactId></dependency></dependencies></project>").getBytes(StandardCharsets.UTF_8));
        files.put(CONTROLLER, CONTROLLER_TEXT.getBytes(StandardCharsets.UTF_8));
        Repo repo = new Repo("acme-bank", name, GITHUB_IDS.incrementAndGet(), "main", sha,
                FakeGitHub.tarball(sha, "acme-bank-" + name + "-" + sha.substring(0, 7), files), true);
        FakeGitHub.get().put(repo);
        return repo;
    }

    /** Created, linked by the approver, registered by staff; attachment optional; not yet submitted. */
    public static AnalysisScenario prepare(ApiIntegrationTest test, ApiClient alice, ApiClient aaron, ApiClient staffClient,
                                           Enrolled registrar, String attachmentText) {
        Repo repo = fakeRepo();
        String projectId = project(alice);
        Map<String, Object> body = complete(projectId);
        body.put("repositoryUrl", "https://github.com/" + repo.owner() + "/" + repo.name());
        Response created = alice.post("/api/v1/requirements", body);
        String requirementId = created.body().path("id").asString();
        String token = alice.post("/api/v1/requirements/" + requirementId + "/repository-link-requests", Map.of())
                .body().path("token").asString();
        Response redeemed = aaron.post("/api/v1/repository-link-requests/redeem",
                Map.of("token", token, "repositoryUrl", "https://github.com/" + repo.owner() + "/" + repo.name()));
        Response registered = staffClient.post("/api/v1/projects/" + projectId + "/repositories",
                Map.of("linkId", redeemed.body().path("linkId").asString()));
        if (registered.status() != 202) {
            throw new IllegalStateException("registration failed: " + registered.status());
        }
        AnalysisScenario scenario = new AnalysisScenario(alice, projectId, requirementId, created, repo,
                TestDatabase.get().tenantId("acme"), registrar);
        if (attachmentText != null) {
            Response uploaded = alice.upload("/api/v1/requirements/" + requirementId + "/documents", "notes.txt",
                    "text/plain", Samples.text(attachmentText));
            if (uploaded.status() != 201) {
                throw new IllegalStateException("upload failed: " + uploaded.status());
            }
            scenario.documentId = uploaded.body().path("documentId").asString();
        }
        return scenario;
    }

    /** A staff member with MFA, assigned to Acme with REPOSITORY_REGISTER, signed in with Acme active. */
    public static Enrolled registrar() {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("registrar"), PASSWORD);
        StaffFixtures.assign(member.email(), "acme");
        StaffFixtures.grant(member.email(), StaffCapability.REPOSITORY_REGISTER);
        return member;
    }

    public static ApiClient staffSession(ApiClient client, Enrolled member, String tenantSlug, int codeOffset) {
        client.post("/api/v1/staff/auth/login", Map.of("email", member.email(), "password", PASSWORD));
        Response verified = client.post("/api/v1/staff/auth/mfa/verify",
                Map.of("code", StaffFixtures.apiCode(member.secret(), codeOffset)));
        if (verified.status() != 200) {
            throw new IllegalStateException("staff MFA failed: " + verified.status());
        }
        client.put("/api/v1/staff/session/active-tenant", Map.of("tenantId", TestDatabase.get().tenantId(tenantSlug)),
                Map.of());
        return client;
    }

    public void submit() {
        Response latest = alice.get("/api/v1/requirements/" + requirementId);
        Response submitted = alice.post("/api/v1/requirements/" + requirementId + "/submit", null, ifMatch(latest), true);
        if (submitted.status() != 200) {
            throw new IllegalStateException("submit failed: " + submitted.status());
        }
    }

    /** Runs the real worker for this tenant until nothing is due. */
    public void drive() {
        drive(WorkerContext.bean(JobWorker.class));
    }

    public void drive(JobWorker worker) {
        OutboxDispatcher dispatcher = WorkerContext.bean(OutboxDispatcher.class);
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

    /** A worker with every real handler except the given job types (to stop a pipeline between steps). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JobWorker workerWithout(String... jobTypes) {
        List<String> excluded = List.of(jobTypes);
        List<JobHandler<?>> handlers = (List) WorkerContext.get().getBeansOfType(JobHandler.class).values().stream()
                .filter(h -> !excluded.contains(h.jobType())).toList();
        return TestWorkers.create(handlers);
    }

    public String state() {
        return TestDatabase.get().admin().queryForObject(
                "SELECT projected_state FROM app.workflow_instance WHERE requirement_id = ?::uuid", String.class,
                requirementId);
    }

    public String documentVersionId() {
        return TestDatabase.get().admin().queryForObject(
                "SELECT id FROM app.document_version WHERE document_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
                String.class, documentId).toString();
    }
}
