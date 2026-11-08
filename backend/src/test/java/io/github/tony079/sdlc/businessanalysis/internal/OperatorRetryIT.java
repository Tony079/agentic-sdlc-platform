package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.modelgateway.internal.FakeModelProvider;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O-27 (owner decision 2026-11-08): bounded automatic retries end DEAD (T19), and an operator with JOB_RETRY can
 * retry a dead BA job of a FAILED requirement after review (T26); permanent failures are refused; the worker
 * re-validates; everything is audited.
 */
class OperatorRetryIT extends ApiIntegrationTest {

    private final JdbcTemplate admin = TestDatabase.get().admin();
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

    private AnalysisScenario failed() {
        Enrolled registrar = AnalysisScenario.registrar();
        AnalysisScenario s = AnalysisScenario.prepare(this, loggedIn("acme", "alice@acme.test"),
                loggedIn("acme", "aaron@acme.test"), AnalysisScenario.staffSession(client(), registrar, "acme", 0),
                registrar, null);
        s.submit();
        fake.cannedReply("ba.phase-a", "this is not json");
        for (int i = 0; i < 4; i++) {
            s.drive();
            admin.update("""
                    UPDATE app.job SET available_at = now() WHERE aggregate_id = ?::uuid AND job_type = 'BA_ANALYSIS'
                      AND status = 'QUEUED'
                    """, s.requirementId);
        }
        s.drive();
        assertThat(s.state()).isEqualTo("FAILED");
        return s;
    }

    private ApiClient operator(boolean capability) {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("ops"), AnalysisScenario.PASSWORD);
        StaffFixtures.assign(member.email(), "acme");
        if (capability) {
            StaffFixtures.grant(member.email(), StaffCapability.JOB_RETRY);
        }
        return AnalysisScenario.staffSession(client(), member, "acme", 0);
    }

    private String deadJob(AnalysisScenario s) {
        return admin.queryForObject("""
                SELECT id::text FROM app.job WHERE aggregate_id = ?::uuid AND job_type = 'BA_ANALYSIS' AND status = 'DEAD'
                """, String.class, s.requirementId);
    }

    @Test
    void anOperatorRetriesADeadAnalysisAfterReview() {
        AnalysisScenario s = failed();
        String job = deadJob(s);
        assertThat(admin.queryForObject("SELECT dead_reason FROM app.job WHERE id = ?::uuid", String.class, job))
                .isEqualTo("RETRIES_EXHAUSTED");

        // Without JOB_RETRY: refused. Client users cannot reach staff endpoints.
        assertThat(operator(false).get("/api/v1/staff/failed-jobs").status()).isEqualTo(403);
        assertThat(s.alice.get("/api/v1/staff/failed-jobs").status()).isIn(401, 403);
        ApiClient ops = operator(true);
        Response list = ops.get("/api/v1/staff/failed-jobs");
        assertThat(list.status()).isEqualTo(200);
        var entry = list.body().valueStream().filter(n -> n.path("id").asString().equals(job)).findFirst().orElseThrow();
        assertThat(entry.path("retryable").asBoolean()).isTrue();
        assertThat(entry.has("payload")).isFalse();

        assertThat(ops.post("/api/v1/staff/failed-jobs/" + job + "/retry", Map.of("reason", "short")).status())
                .isEqualTo(422);
        // The model is fixed (operator reviewed the failure); now retry.
        fake.reset();
        fake.cannedReply("ba.phase-a", """
                {"understanding": "Loans.", "components": [], "relevantPaths": ["%s"]}""".formatted(AnalysisScenario.CONTROLLER));
        fake.cannedReply("ba.phase-b", """
                {"items": [{"kind": "FACT", "section": "FACTS", "statement": "Loans are served by the controller mapped to /loans.",
                  "evidence": [{"type": "REPOSITORY", "path": "%s", "startLine": 3, "endLine": 6, "documentVersionId": null,
                  "chunk": null, "quote": "RequestMapping(\\"/loans\\")"}], "risk": null, "component": null}],
                 "unsupported": [], "clarifications": []}""".formatted(AnalysisScenario.CONTROLLER));
        Response retry = ops.post("/api/v1/staff/failed-jobs/" + job + "/retry",
                Map.of("reason", "Model output fixed after review of the failure."));
        assertThat(retry.status()).as(retry.body().toString()).isEqualTo(200);
        // A duplicate request is refused while one is pending (no duplicate side effects).
        assertThat(ops.post("/api/v1/staff/failed-jobs/" + job + "/retry",
                Map.of("reason", "Model output fixed after review of the failure.")).errorCode())
                .isEqualTo("RETRY_ALREADY_REQUESTED");
        s.drive();
        assertThat(admin.queryForObject("SELECT status FROM app.admin_retry_request WHERE job_id = ?::uuid",
                String.class, UUID.fromString(job))).isEqualTo("COMPLETED");
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        assertThat(admin.queryForList("""
                SELECT t.transition FROM app.workflow_transition t JOIN app.workflow_instance i ON i.id = t.instance_id
                WHERE i.requirement_id = ?::uuid ORDER BY t.seq
                """, String.class, s.requirementId)).containsSubsequence("T19_ANALYSIS_FAILED", "T26_ADMIN_RETRY",
                "T18_BRD_DRAFTED");
        // The dead job stays dead (history kept); the retry ran as a new job.
        assertThat(admin.queryForObject("SELECT status FROM app.job WHERE id = ?::uuid", String.class, job))
                .isEqualTo("DEAD");
        assertThat(admin.queryForList("SELECT action FROM app.audit_event WHERE action LIKE 'ADMIN_RETRY_%'",
                String.class)).contains("ADMIN_RETRY_REQUESTED", "ADMIN_RETRY_COMPLETED");
        // No longer FAILED: a second retry is refused.
        assertThat(ops.post("/api/v1/staff/failed-jobs/" + job + "/retry",
                Map.of("reason", "Model output fixed after review of the failure.")).errorCode()).isEqualTo("NOT_FAILED");
    }

    @Test
    void permanentFailuresAreNeverRetried() throws Exception {
        AnalysisScenario s = failed();
        String job = deadJob(s);
        // A policy failure (budget cap) is permanent: refused even for an operator.
        try (var c = TestDatabase.get().adminConnection(); var st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.executeUpdate("UPDATE app.job SET dead_reason = 'FATAL_FAILURE', last_error_code = 'COST_CAP' WHERE id = '"
                    + job + "'");
        }
        ApiClient ops = operator(true);
        assertThat(ops.post("/api/v1/staff/failed-jobs/" + job + "/retry",
                Map.of("reason", "Trying to bypass the budget cap.")).errorCode()).isEqualTo("PERMANENT_FAILURE");
        assertThat(OperatorRetryService.retryable("REPO_VALIDATE", "RETRIES_EXHAUSTED", "GITHUB_TRANSIENT")).isFalse();
        assertThat(OperatorRetryService.retryable("BA_ANALYSIS", "RETRIES_EXHAUSTED", "MODEL_INVALID_OUTPUT")).isTrue();
        // An unknown job id gives 404 (no disclosure).
        assertThat(ops.post("/api/v1/staff/failed-jobs/" + UUID.randomUUID() + "/retry",
                Map.of("reason", "Retry of an unknown job.")).status()).isEqualTo(404);
    }
}
