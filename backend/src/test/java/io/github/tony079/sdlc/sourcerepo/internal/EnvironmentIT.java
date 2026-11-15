package io.github.tony079.sdlc.sourcerepo.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.outbox.internal.OutboxDispatcher;
import io.github.tony079.sdlc.queue.internal.JdbcJobStore;
import io.github.tony079.sdlc.queue.internal.JobWorker;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.FakeGitHub;
import io.github.tony079.sdlc.support.FakeGitHub.Repo;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GD-20/GD-21: the project's Environment Manager connects GitHub repositories and defines environments with a branch
 * per repository; the worker verifies them through the GitHub App; anything touching PROD is a request approved by a
 * BRD Approver of the project who is not the requester; other members cannot change environments.
 */
class EnvironmentIT extends ApiIntegrationTest {

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();

    private void drive(UUID tenant) {
        OutboxDispatcher dispatcher = WorkerContext.bean(OutboxDispatcher.class);
        JobWorker worker = WorkerContext.bean(JobWorker.class);
        for (int round = 0; round < 10; round++) {
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

    @Test
    void theEnvironmentManagerSetsUpEnvironmentsAndProdChangesNeedABrdApprover() {
        WorkerContext.get();
        UUID acme = db.tenantId("acme");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sha = "a".repeat(39) + "1";
        FakeGitHub.get().put(new Repo("acme-env", "payments-" + suffix, System.nanoTime() % 1_000_000_000L + 1, "main", sha,
                FakeGitHub.tarball(sha, "x", Map.of()), true));

        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        String project = aaron.post("/api/v1/projects", Map.of("name", "Envs " + suffix)).body().path("id").asString();
        admin.update("UPDATE app.project SET status = 'ACTIVE', activated_at = now() WHERE id = ?::uuid", project);
        String aliceId = admin.queryForObject("SELECT id::text FROM app.app_user WHERE email = 'alice@acme.test'", String.class);
        String aaronId = admin.queryForObject("SELECT id::text FROM app.app_user WHERE email = 'aaron@acme.test'", String.class);
        aaron.post("/api/v1/projects/" + project + "/members", Map.of("userId", aliceId, "role", "REQUIREMENT_AUTHOR"));
        aaron.post("/api/v1/projects/" + project + "/members", Map.of("userId", aaronId, "role", "BRD_APPROVER"));

        // Before an Environment Manager is named, nobody changes environments.
        assertThat(alice.post("/api/v1/projects/" + project + "/environments", Map.of("name", "QC")).errorCode())
                .isEqualTo("ENVIRONMENT_MANAGER_REQUIRED");
        aaron.put("/api/v1/projects/" + project + "/environment-manager", Map.of("userId", aliceId));

        // Connect a repository; the worker verifies the App installation.
        Response connected = alice.post("/api/v1/projects/" + project + "/connected-repositories",
                Map.of("repository", "https://github.com/acme-env/payments-" + suffix));
        assertThat(connected.status()).as(connected.body().toString()).isEqualTo(201);
        String repo = connected.body().path("id").asString();
        assertThat(aaron.post("/api/v1/projects/" + project + "/environments", Map.of("name", "QC")).errorCode())
                .isEqualTo("ENVIRONMENT_MANAGER_REQUIRED");

        // Non-PROD environments and branches apply at once.
        assertThat(alice.post("/api/v1/projects/" + project + "/environments", Map.of("name", "QC")).status()).isEqualTo(201);
        assertThat(alice.post("/api/v1/projects/" + project + "/environments", Map.of("name", "PROD")).status()).isEqualTo(201);
        Response overview = alice.get("/api/v1/projects/" + project + "/environments");
        String qc = null;
        String prod = null;
        for (var env : overview.body().path("environments")) {
            if (env.path("name").asString().equals("QC")) {
                qc = env.path("id").asString();
            } else {
                prod = env.path("id").asString();
            }
        }
        Response branch = alice.post("/api/v1/environments/" + qc + "/branches", Map.of("repositoryId", repo, "branch", "release/qc"));
        assertThat(branch.body().path("applied").asBoolean()).isTrue();
        drive(acme);
        overview = alice.get("/api/v1/projects/" + project + "/environments");
        assertThat(overview.body().path("repositories").get(0).path("status").asString()).isEqualTo("VERIFIED");
        assertThat(overview.body().toString()).contains("\"status\":\"VERIFIED\"").contains(sha);

        // Marking PROD is a request; the requester cannot approve; a BRD Approver does.
        Response mark = alice.post("/api/v1/environments/" + prod + "/mark-production", Map.of("reason", "Live customer traffic."));
        assertThat(mark.body().path("applied").asBoolean()).isFalse();
        String markId = mark.body().path("requestId").asString();
        assertThat(alice.post("/api/v1/environment-requests/" + markId + "/approve", Map.of()).errorCode())
                .isEqualTo("BRD_APPROVER_REQUIRED");
        assertThat(aaron.post("/api/v1/environment-requests/" + markId + "/approve", Map.of()).status()).isEqualTo(200);

        // Now every change to PROD waits for approval.
        Response prodBranch = alice.post("/api/v1/environments/" + prod + "/branches",
                Map.of("repositoryId", repo, "branch", "main", "reason", "Production branch."));
        assertThat(prodBranch.body().path("applied").asBoolean()).isFalse();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.environment_branch WHERE environment_id = ?::uuid",
                Integer.class, prod)).isZero();
        assertThat(aaron.post("/api/v1/environment-requests/" + prodBranch.body().path("requestId").asString() + "/approve",
                Map.of()).status()).isEqualTo(200);
        assertThat(admin.queryForObject("SELECT branch FROM app.environment_branch WHERE environment_id = ?::uuid",
                String.class, prod)).isEqualTo("main");
        assertThat(alice.delete("/api/v1/environments/" + prod + "?reason=").errorCode()).isEqualTo("VALIDATION_FAILED");

        // The requirement's owner chooses its target environment; others see it but cannot change it.
        String requirement = alice.post("/api/v1/requirements",
                io.github.tony079.sdlc.support.RequirementFixtures.complete(project)).body().path("id").asString();
        assertThat(alice.put("/api/v1/requirements/" + requirement + "/target-environment",
                Map.of("environmentId", qc)).status()).isEqualTo(204);
        Response target = alice.get("/api/v1/requirements/" + requirement + "/target-environment");
        assertThat(target.body().path("environmentId").asString()).isEqualTo(qc);
        assertThat(target.body().path("canChange").asBoolean()).isTrue();
        assertThat(aaron.put("/api/v1/requirements/" + requirement + "/target-environment",
                Map.of("environmentId", prod)).errorCode()).isEqualTo("OWNER_REQUIRED");
    }
}
