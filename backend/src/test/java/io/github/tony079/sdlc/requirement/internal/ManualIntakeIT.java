package io.github.tony079.sdlc.requirement.internal;

import static io.github.tony079.sdlc.support.RequirementFixtures.complete;
import static io.github.tony079.sdlc.support.RequirementFixtures.ifMatch;
import static io.github.tony079.sdlc.support.RequirementFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.outbox.internal.OutboxDispatcher;
import io.github.tony079.sdlc.queue.internal.JdbcJobStore;
import io.github.tony079.sdlc.queue.internal.JobWorker;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;
import io.github.tony079.sdlc.workflow.internal.ClosureRecorder;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * C-06/C-07 (owner decision 2026-11-08): a Business Reviewer resumes (T04) or closes (T05) a manual-intake requirement;
 * O-25 closure is recorded once, immutably, after the follow-up work ends. AC-EXT-28, AC-EXT-42.
 */
class ManualIntakeIT extends ApiIntegrationTest {

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();
    private final UUID acme = db.tenantId("acme");

    private void drive() {
        WorkerContext.get();
        OutboxDispatcher dispatcher = WorkerContext.bean(OutboxDispatcher.class);
        JobWorker worker = WorkerContext.bean(JobWorker.class);
        for (int round = 0; round < 25; round++) {
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

    private String state(String requirementId) {
        return admin.queryForObject("SELECT projected_state FROM app.workflow_instance WHERE requirement_id = ?::uuid",
                String.class, requirementId);
    }

    /** A NEW_PRODUCT requirement, submitted and routed to MANUAL_INTAKE by the worker. */
    private String manualIntake(ApiClient alice) {
        Map<String, Object> body = complete(project(alice));
        body.put("kind", "NEW_PRODUCT");
        body.remove("repositoryUrl");
        Response created = alice.post("/api/v1/requirements", body);
        String id = created.body().path("id").asString();
        Response latest = alice.get("/api/v1/requirements/" + id);
        assertThat(alice.post("/api/v1/requirements/" + id + "/submit", null, ifMatch(latest), true).status())
                .isEqualTo(200);
        drive();
        assertThat(state(id)).isEqualTo("MANUAL_INTAKE");
        return id;
    }

    private ApiClient reviewer() {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("intake"), AnalysisScenario.PASSWORD);
        StaffFixtures.assign(member.email(), "acme");
        StaffFixtures.grant(member.email(), StaffCapability.BUSINESS_REVIEWER);
        return AnalysisScenario.staffSession(client(), member, "acme", 0);
    }

    private Response resume(ApiClient staff, String id, Object revision) {
        return staff.post("/api/v1/staff/requirements/" + id + "/resume",
                Map.of("expectedRevisionNo", revision, "reason", "Repository identified by the client."));
    }

    @Test
    void reviewerResumesAManualIntakeRequirementOnceARepositoryIsAuthorised() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        String id = manualIntake(alice);
        ApiClient reviewer = reviewer();

        Response queue = reviewer.get("/api/v1/staff/manual-intake");
        assertThat(queue.status()).isEqualTo(200);
        assertThat(queue.body().valueStream().map(n -> n.path("id").asString())).contains(id);

        // No authorised repository link yet: refused, nothing changes.
        assertThat(resume(reviewer, id, 1).errorCode()).isEqualTo("REPOSITORY_LINK_REQUIRED");
        // The owner issues a one-time link request; the Client Approver authorises a repository (C-18).
        var repo = AnalysisScenario.fakeRepo();
        String url = "https://github.com/" + repo.owner() + "/" + repo.name();
        String token = alice.post("/api/v1/requirements/" + id + "/repository-link-requests", Map.of())
                .body().path("token").asString();
        assertThat(aaron.post("/api/v1/repository-link-requests/redeem", Map.of("token", token, "repositoryUrl", url))
                .status()).isEqualTo(201);

        // Validation: reason length, revision required, stale revision.
        assertThat(reviewer.post("/api/v1/staff/requirements/" + id + "/resume",
                Map.of("expectedRevisionNo", 1, "reason", "short")).status()).isEqualTo(422);
        assertThat(resume(reviewer, id, 7).errorCode()).isEqualTo("STALE_REVISION");
        // Client users and staff without the capability cannot resume.
        assertThat(alice.post("/api/v1/staff/requirements/" + id + "/resume",
                Map.of("expectedRevisionNo", 1, "reason", "Repository identified by the client.")).status())
                .isIn(401, 403);
        ApiClient registrar = AnalysisScenario.staffSession(client(), AnalysisScenario.registrar(), "acme", 0);
        assertThat(resume(registrar, id, 1).status()).isEqualTo(403);
        // Another tenant's reviewer cannot see it.
        assertThat(admin.queryForObject("SELECT count(*) FROM app.workflow_transition WHERE transition = "
                + "'T04_RESUME_MANUAL_INTAKE' AND instance_id = (SELECT id FROM app.workflow_instance "
                + "WHERE requirement_id = ?::uuid)", Integer.class, id)).isZero();

        Response resumed = resume(reviewer, id, 1);
        assertThat(resumed.status()).as(resumed.body().toString()).isEqualTo(200);
        assertThat(resumed.body().path("state").asString()).isEqualTo("REPO_VALIDATING");
        // FR-REQ-11: a new revision converts it, the owner remains the author, the reviewer is recorded.
        Map<String, Object> revision = admin.queryForMap("""
                SELECT kind, repository_url, converted_by_staff IS NOT NULL AS converted, created_by
                FROM app.requirement_revision WHERE requirement_id = ?::uuid AND revision_no = 2
                """, id);
        assertThat(revision).containsEntry("kind", "EXISTING_REPOSITORY").containsEntry("repository_url", url)
                .containsEntry("converted", true);
        assertThat(admin.queryForObject("SELECT reason FROM app.workflow_transition WHERE transition = "
                + "'T04_RESUME_MANUAL_INTAKE'  AND instance_id = (SELECT id FROM app.workflow_instance "
                + "WHERE requirement_id = ?::uuid)", String.class, id)).isEqualTo("Repository identified by the client.");
        assertThat(admin.queryForList("SELECT action FROM app.audit_event WHERE resource_id = ?::uuid", String.class,
                UUID.fromString(id))).contains("MANUAL_INTAKE_RESUMED");
        // The same workflow continues: the worker schedules repository validation (no duplicate requirement).
        drive();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.job WHERE aggregate_id = ?::uuid AND job_type = "
                + "'REPO_VALIDATE'", Integer.class, UUID.fromString(id))).isEqualTo(1);
        // A second resume is refused (no longer MANUAL_INTAKE).
        assertThat(resume(reviewer, id, 2).errorCode()).isEqualTo("NOT_IN_MANUAL_INTAKE");
    }

    @Test
    void reviewerClosesWithAReasonAndClosureIsRecordedOnceWithoutReopening() throws Exception {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        String id = manualIntake(alice);
        ApiClient reviewer = reviewer();

        assertThat(reviewer.post("/api/v1/staff/requirements/" + id + "/close", Map.of("reason", "no")).status())
                .isEqualTo(422);
        Response closed = reviewer.post("/api/v1/staff/requirements/" + id + "/close",
                Map.of("reason", "Out of scope for the current engagement."));
        assertThat(closed.status()).as(closed.body().toString()).isEqualTo(200);
        assertThat(closed.body().path("state").asString()).isEqualTo("CANCELLED");
        drive();
        // The owner is told in-app (AC-EXT-40: no email).
        assertThat(alice.get("/api/v1/notifications").body().valueStream().map(n -> n.path("kind").asString()))
                .contains("REQUIREMENT_CLOSED");
        // A closed requirement cannot be resumed (no reopening transition).
        assertThat(resume(reviewer, id, 1).errorCode()).isEqualTo("NOT_IN_MANUAL_INTAKE");

        ClosureRecorder recorder = WorkerContext.bean(ClosureRecorder.class);
        recorder.recordEligible();
        Map<String, Object> closure = admin.queryForMap("""
                SELECT terminal_state, transition, closed_by_type, closed_by_id IS NOT NULL AS has_actor, closed_at
                FROM app.requirement_closure WHERE requirement_id = ?::uuid
                """, id);
        assertThat(closure).containsEntry("terminal_state", "CANCELLED")
                .containsEntry("transition", "T05_CLOSE_MANUAL_INTAKE").containsEntry("closed_by_type", "STAFF")
                .containsEntry("has_actor", true);
        // Recorded once and immutable: a second run changes nothing; runtime logins cannot update or delete it.
        recorder.recordEligible();
        assertThat(admin.queryForObject("SELECT closed_at FROM app.requirement_closure WHERE requirement_id = ?::uuid",
                Object.class, id)).isEqualTo(closure.get("closed_at"));
        for (var connection : new java.sql.Connection[] {db.appConnection(), db.workerConnection()}) {
            try (connection; var st = connection.createStatement()) {
                assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> st.executeUpdate(
                        "UPDATE app.requirement_closure SET closed_at = now()"))).hasMessageContaining("permission denied");
                assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> st.executeUpdate(
                        "DELETE FROM app.requirement_closure"))).hasMessageContaining("permission denied");
            }
        }
    }

    @Test
    void approvalOrReleaseAloneDoesNotCloseARequirement() {
        // A requirement still in MANUAL_INTAKE (non-terminal) and a SUBMITTED one are never recorded as closed.
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        String id = manualIntake(alice);
        WorkerContext.bean(ClosureRecorder.class).recordEligible();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.requirement_closure WHERE requirement_id = ?::uuid",
                Integer.class, id)).isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.requirement_closure c JOIN app.workflow_instance i ON i.requirement_id = c.requirement_id
                WHERE i.projected_state NOT IN ('CANCELLED', 'REJECTED')
                """, Integer.class)).isZero();
    }
}
