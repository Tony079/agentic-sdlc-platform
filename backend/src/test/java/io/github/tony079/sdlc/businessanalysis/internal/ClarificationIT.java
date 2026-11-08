package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.modelgateway.internal.FakeModelProvider;
import io.github.tony079.sdlc.modelgateway.internal.FakeModelProvider.Reply;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.Samples;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PRD FR-CLR / TDD 17 (AC-CORE-06, AC-CORE-14) and document decisions (AC-EXT-23, AC-EXT-31) and input changes after
 * analysis (AC-DOC-14, AC-EXT-41), end to end with the fake model provider (TEST ONLY).
 */
class ClarificationIT extends ApiIntegrationTest {

    private static final String PHASE_A = """
            {"understanding": "Repayment metadata.", "components": [], "relevantPaths": ["%s"]}"""
            .formatted(AnalysisScenario.CONTROLLER);
    private static final String ITEMS = """
            [{"kind": "FACT", "section": "FACTS", "statement": "Loans are served by the controller mapped to /loans.",
              "evidence": [{"type": "REPOSITORY", "path": "%s", "startLine": 3, "endLine": 6,
                            "documentVersionId": null, "chunk": null, "quote": "RequestMapping(\\"/loans\\")"}],
              "risk": null, "component": "Loan API"}]""".formatted(AnalysisScenario.CONTROLLER);
    private static final String NO_CLARIFICATIONS = "{\"items\": " + ITEMS + ", \"unsupported\": [], \"clarifications\": []}";

    private static String withClarification(boolean blocking, String assignee) {
        return "{\"items\": " + ITEMS + ", \"unsupported\": [], \"clarifications\": [{\"question\": "
                + "\"Which repayment channels must be supported?\", \"whyItMatters\": \"It decides the field values.\", "
                + "\"interpretations\": [\"Card only\", \"Card and bank transfer\"], \"recommendedOption\": "
                + "\"Card and bank transfer\", \"blocking\": " + blocking + ", \"assignedTo\": \"" + assignee + "\"}]}";
    }

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();
    private FakeModelProvider fake;

    @BeforeEach
    void setUp() {
        WorkerContext.get();
        fake = WorkerContext.bean(FakeModelProvider.class);
        fake.reset();
        fake.cannedReply("ba.phase-a", PHASE_A);
        fake.cannedReply("ba.phase-b", NO_CLARIFICATIONS);
    }

    @AfterEach
    void tearDown() {
        fake.reset();
    }

    private AnalysisScenario scenario(String attachment) {
        Enrolled registrar = AnalysisScenario.registrar();
        return AnalysisScenario.prepare(this, loggedIn("acme", "alice@acme.test"), loggedIn("acme", "aaron@acme.test"),
                AnalysisScenario.staffSession(client(), registrar, "acme", 0), registrar, attachment);
    }

    private ApiClient reviewer() {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("reviewer"), AnalysisScenario.PASSWORD);
        StaffFixtures.assign(member.email(), "acme");
        StaffFixtures.grant(member.email(), StaffCapability.BUSINESS_REVIEWER);
        return AnalysisScenario.staffSession(client(), member, "acme", 0);
    }

    private String clarificationId(AnalysisScenario s) {
        return admin.queryForObject("SELECT id::text FROM app.clarification WHERE requirement_id = ?::uuid ORDER BY created_at DESC LIMIT 1",
                String.class, s.requirementId);
    }

    private List<String> transitions(AnalysisScenario s) {
        return admin.queryForList("""
                SELECT t.transition FROM app.workflow_transition t JOIN app.workflow_instance i ON i.id = t.instance_id
                WHERE i.requirement_id = ?::uuid ORDER BY t.seq
                """, String.class, s.requirementId);
    }

    @Test
    void aBlockingAgentQuestionPausesTheAnalysisUntilTheOwnerAnswers() {
        fake.enqueue(new Reply(PHASE_A), new Reply(withClarification(true, "CLIENT")));
        AnalysisScenario s = scenario(null);
        s.submit();
        s.drive();

        assertThat(s.state()).isEqualTo("AWAITING_CLARIFICATION");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.brd_version WHERE requirement_id = ?::uuid",
                Integer.class, s.requirementId)).isZero();
        assertThat(admin.queryForObject("SELECT status FROM app.ba_result_pending WHERE requirement_id = ?::uuid",
                String.class, s.requirementId)).isEqualTo("PAUSED");
        Map<String, Object> c = admin.queryForMap("""
                SELECT source, blocking, assigned_role, status, revision_no, commit_sha, stage_execution_id IS NOT NULL AS has_stage
                FROM app.clarification WHERE requirement_id = ?::uuid
                """, s.requirementId);
        assertThat(c).containsEntry("source", "AGENT").containsEntry("blocking", true)
                .containsEntry("assigned_role", "REQUIREMENT_OWNER").containsEntry("status", "OPEN")
                .containsEntry("commit_sha", s.repo.headSha()).containsEntry("has_stage", true);
        String id = clarificationId(s);

        // Inbox and badge for the owner (AC-CORE-14); nobody else in the tenant sees it.
        Response inbox = s.alice.get("/api/v1/clarifications");
        assertThat(inbox.body().valueStream().map(n -> n.path("id").asString())).contains(id);
        assertThat(s.alice.get("/api/v1/notifications").body().valueStream().map(n -> n.path("kind").asString()))
                .contains("CLARIFICATION_ASSIGNED");
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        assertThat(aaron.get("/api/v1/clarifications/" + id).status()).isEqualTo(404);
        assertThat(aaron.post("/api/v1/clarifications/" + id + "/answer", Map.of("text", "x")).status()).isEqualTo(404);
        // A reviewer cannot answer a question assigned to the owner, and the model never answers.
        ApiClient reviewer = reviewer();
        assertThat(reviewer.post("/api/v1/staff/clarifications/" + id + "/answer", Map.of("text", "Card")).errorCode())
                .isEqualTo("NOT_ASSIGNED_ROLE");
        assertThat(s.alice.post("/api/v1/clarifications/" + id + "/answer", Map.of("text", "")).status()).isEqualTo(422);

        Response answered = s.alice.post("/api/v1/clarifications/" + id + "/answer",
                Map.of("text", "Card and bank transfer."));
        assertThat(answered.body().path("status").asString()).isEqualTo("ANSWERED");
        s.drive();

        assertThat(admin.queryForObject("SELECT status FROM app.clarification WHERE id = ?::uuid", String.class, id))
                .isEqualTo("RESOLVED");
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        assertThat(transitions(s)).containsSubsequence("T11_START_ANALYSIS", "T16_AWAIT_CLARIFICATION",
                "T17_RESUME_ANALYSIS", "T18_BRD_DRAFTED", "T20_QUEUE_REVIEW");
        // The resumed execution received the answer as input, and the paused one was superseded (FR-CLR-10).
        assertThat(admin.queryForObject("""
                SELECT corrections::text FROM app.analysis_input_set WHERE requirement_id = ?::uuid ORDER BY created_at DESC LIMIT 1
                """, String.class, s.requirementId)).contains("Card and bank transfer.").contains("CLARIFICATION");
        assertThat(admin.queryForList("""
                SELECT s.status FROM app.stage_execution s JOIN app.workflow_instance i ON i.id = s.instance_id
                WHERE i.requirement_id = ?::uuid AND s.stage_key = 'BA_ANALYSIS' ORDER BY s.attempt_no
                """, String.class, s.requirementId)).containsExactly("SUPERSEDED", "SUCCEEDED");
        Map<String, Object> answer = admin.queryForMap("""
                SELECT author_type, author_id FROM app.clarification_message WHERE clarification_id = ?::uuid AND kind = 'ANSWER'
                """, id);
        assertThat(answer).containsEntry("author_type", "USER").containsEntry("author_id",
                admin.queryForObject("SELECT id FROM app.app_user WHERE email = 'alice@acme.test'", java.util.UUID.class));
        assertThat(admin.queryForList("SELECT action FROM app.audit_event WHERE resource_id = ?::uuid", String.class,
                java.util.UUID.fromString(id))).contains("CLARIFICATION_ANSWERED", "CLARIFICATION_RESOLVED");
    }

    @Test
    void aNonBlockingQuestionDoesNotStopTheRun() {
        fake.enqueue(new Reply(PHASE_A), new Reply(withClarification(false, "BUSINESS_REVIEWER")));
        AnalysisScenario s = scenario(null);
        s.submit();
        s.drive();
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        assertThat(admin.queryForMap("SELECT blocking, status, assigned_role FROM app.clarification WHERE requirement_id = ?::uuid",
                s.requirementId)).containsEntry("blocking", false).containsEntry("status", "OPEN")
                .containsEntry("assigned_role", "BUSINESS_REVIEWER");
        ApiClient reviewer = reviewer();
        String id = clarificationId(s);
        assertThat(reviewer.get("/api/v1/staff/clarifications").body().valueStream()
                .map(n -> n.path("id").asString())).contains(id);
        assertThat(reviewer.post("/api/v1/staff/clarifications/" + id + "/answer", Map.of("text", "Both channels."))
                .body().path("status").asString()).isEqualTo("ANSWERED");
    }

    @Test
    void aReviewerQuestionBlocksApprovalUntilAnswered() {
        AnalysisScenario s = scenario(null);
        s.submit();
        s.drive();
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        ApiClient reviewer = reviewer();
        Map<String, Object> version = admin.queryForMap("""
                SELECT id::text AS id, content_hash FROM app.brd_version WHERE requirement_id = ?::uuid AND status = 'IN_REVIEW'
                """, s.requirementId);
        Response raised = reviewer.post("/api/v1/staff/brd-versions/" + version.get("id") + "/clarifications",
                Map.of("question", "Is the reference field mandatory?", "assignedRole", "REQUIREMENT_OWNER", "blocking", true));
        assertThat(raised.status()).isEqualTo(201);
        String id = raised.body().path("clarificationId").asString();
        Response blocked = reviewer.post("/api/v1/staff/brd-versions/" + version.get("id") + "/approve",
                Map.of("expectedContentHash", version.get("content_hash")));
        assertThat(blocked.errorCode()).isEqualTo("OPEN_CLARIFICATIONS");

        // Reassign to the internal analyst and back, request more information, then the owner answers.
        assertThat(reviewer.post("/api/v1/staff/clarifications/" + id + "/reassign",
                Map.of("role", "BUSINESS_REVIEWER", "reason", "Internal knowledge")).status()).isEqualTo(200);
        assertThat(reviewer.post("/api/v1/staff/clarifications/" + id + "/reassign",
                Map.of("role", "REQUIREMENT_OWNER", "reason", "Client decision")).status()).isEqualTo(200);
        s.alice.post("/api/v1/clarifications/" + id + "/answer", Map.of("text", "Yes"));
        assertThat(reviewer.post("/api/v1/staff/clarifications/" + id + "/request-info",
                Map.of("reason", "Please give the maximum length.")).body().path("status").asString()).isEqualTo("NEEDS_INFO");
        s.alice.post("/api/v1/clarifications/" + id + "/answer", Map.of("text", "Yes, at most 35 characters."));
        s.drive();
        assertThat(admin.queryForObject("SELECT status FROM app.clarification WHERE id = ?::uuid", String.class, id))
                .isEqualTo("RESOLVED");
        assertThat(admin.queryForList("SELECT kind FROM app.clarification_message WHERE clarification_id = ?::uuid ORDER BY created_at",
                String.class, id)).containsExactly("REASSIGN", "REASSIGN", "ANSWER", "INFO_REQUEST", "ANSWER");
        Response approved = reviewer.post("/api/v1/staff/brd-versions/" + version.get("id") + "/approve",
                Map.of("expectedContentHash", version.get("content_hash")));
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
    }

    @Test
    void cancellationWithdrawsOpenClarifications() {
        fake.enqueue(new Reply(PHASE_A), new Reply(withClarification(true, "CLIENT")));
        AnalysisScenario s = scenario(null);
        s.submit();
        s.drive();
        Response latest = s.alice.get("/api/v1/requirements/" + s.requirementId);
        assertThat(s.alice.post("/api/v1/requirements/" + s.requirementId + "/cancel",
                Map.of("reason", "No longer needed by the business"),
                io.github.tony079.sdlc.support.RequirementFixtures.ifMatch(latest), true).status()).isEqualTo(200);
        s.drive();
        assertThat(admin.queryForObject("SELECT status FROM app.clarification WHERE requirement_id = ?::uuid",
                String.class, s.requirementId)).isEqualTo("WITHDRAWN");
    }

    @Test
    void aDocumentNeedingADecisionWaitsForTheReviewerThenAnalysisStarts() {
        AnalysisScenario s = scenario("Scanned notes " + java.util.UUID.randomUUID());
        admin.update("""
                UPDATE app.document_version SET state = 'UNSUPPORTED_CONTENT', scan_result = 'CLEAN', scanned_at = now(),
                       scanner = 'test' WHERE document_id = ?::uuid
                """, s.documentId);
        s.submit();
        s.drive();
        assertThat(s.state()).isEqualTo("AWAITING_DOCUMENT_DECISION");
        String versionId = s.documentVersionId();
        // The client cannot exclude unsupported content (Business Reviewer authorisation needed, AC-EXT-23).
        assertThat(s.alice.post("/api/v1/requirements/" + s.requirementId + "/documents/" + s.documentId
                + "/acknowledge-exclusion", Map.of("reason", "Not needed")).errorCode()).isEqualTo("DECISION_NOT_ALLOWED");
        ApiClient reviewer = reviewer();
        assertThat(reviewer.get("/api/v1/staff/document-decisions").body().valueStream()
                .map(n -> n.path("versionId").asString())).contains(versionId);
        assertThat(reviewer.post("/api/v1/staff/document-decisions",
                Map.of("versionId", versionId, "reason", "Image-only scan; content not required")).status()).isEqualTo(204);
        assertThat(reviewer.post("/api/v1/staff/document-decisions",
                Map.of("versionId", versionId, "reason", "again")).errorCode()).isEqualTo("DECISION_EXISTS");
        s.drive();
        assertThat(transitions(s)).containsSubsequence("T07_REPO_VALIDATED_DOCUMENT_DECISION",
                "T13_START_ANALYSIS_AFTER_DECISION", "T18_BRD_DRAFTED");
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        assertThat(admin.queryForObject("SELECT excluded_documents::text FROM app.brd_version WHERE requirement_id = ?::uuid",
                String.class, s.requirementId)).contains("UNSUPPORTED_CONTENT");
        assertThat(admin.queryForMap("SELECT decision, actor_type FROM app.document_decision WHERE document_version_id = ?::uuid",
                versionId)).containsEntry("decision", "EXCLUDE").containsEntry("actor_type", "STAFF");
    }

    @Test
    void addingADocumentAfterAnalysisMakesTheReviewVersionStale() {
        AnalysisScenario s = scenario(null);
        s.submit();
        s.drive();
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        String versionId = admin.queryForObject("SELECT id::text FROM app.brd_version WHERE requirement_id = ?::uuid",
                String.class, s.requirementId);
        Response uploaded = s.alice.upload("/api/v1/requirements/" + s.requirementId + "/documents", "late.txt",
                "text/plain", Samples.text("Late addition " + java.util.UUID.randomUUID()));
        assertThat(uploaded.status()).isEqualTo(201);
        s.drive();
        assertThat(admin.queryForObject("SELECT status FROM app.brd_version WHERE id = ?::uuid", String.class, versionId))
                .isEqualTo("STALE");
        ApiClient reviewer = reviewer();
        assertThat(reviewer.post("/api/v1/staff/brd-versions/" + versionId + "/approve",
                Map.of("expectedContentHash", "x")).errorCode()).isEqualTo("APPROVAL_CONFLICT");
    }

    @Test
    void addingADocumentDuringAnalysisCancelsTheRunAndRestartsWithIt() {
        AnalysisScenario s = scenario(null);
        s.submit();
        s.drive(AnalysisScenario.workerWithout("BA_ANALYSIS"));
        assertThat(s.state()).isEqualTo("ANALYSING");
        s.alice.upload("/api/v1/requirements/" + s.requirementId + "/documents", "late.txt", "text/plain",
                Samples.text("Late addition " + java.util.UUID.randomUUID()));
        s.drive(AnalysisScenario.workerWithout("BA_ANALYSIS", "DOCUMENT_SCAN"));
        assertThat(s.state()).isEqualTo("AWAITING_DOCUMENTS");
        assertThat(admin.queryForObject("""
                SELECT status FROM app.job WHERE aggregate_id = ?::uuid AND job_type = 'BA_ANALYSIS' ORDER BY created_at LIMIT 1
                """, String.class, s.requirementId)).isEqualTo("CANCELLED");
        s.drive();
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        assertThat(transitions(s)).containsSubsequence("T11_START_ANALYSIS", "T14_ANALYSIS_STALE_WAIT",
                "T11_START_ANALYSIS", "T18_BRD_DRAFTED");
        assertThat(admin.queryForObject("""
                SELECT documents::text FROM app.analysis_input_set WHERE requirement_id = ?::uuid ORDER BY created_at DESC LIMIT 1
                """, String.class, s.requirementId)).contains("late.txt");
    }
}
