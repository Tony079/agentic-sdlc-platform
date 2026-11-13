package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
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
 * R1-M6 end to end (fake model provider, TEST ONLY): Business Reviewer queue and detail, flag acknowledgement,
 * approval T1 bound to the version hash (T21), automatic release T25 in a separate transaction, client view without
 * excerpts and 404 before release, notifications, client changes (T27/T28) producing v2 and invalidating v1's approval,
 * revision request (T22/T24) with four-eyes, rejection (T23), cross-tenant isolation.
 */
class ReviewIT extends ApiIntegrationTest {

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();
    private FakeModelProvider fake;

    @BeforeEach
    void setUp() {
        WorkerContext.get();
        fake = WorkerContext.bean(FakeModelProvider.class);
        fake.reset();
        fake.cannedReply("ba.phase-a", """
                {"understanding": "Repayment metadata on the loan API.", "components": [],
                 "relevantPaths": ["%s"]}""".formatted(AnalysisScenario.CONTROLLER));
        fake.cannedReply("ba.phase-b", """
                {"items": [
                  {"kind": "FACT", "section": "FACTS", "statement": "Loans are served by the controller mapped to /loans.",
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 3, "endLine": 6,
                                 "documentVersionId": null, "chunk": null, "quote": "RequestMapping(\\"/loans\\")"}],
                   "risk": null, "component": "Loan API"},
                  {"kind": "FACT", "section": "FACTS", "statement": "The billing module encrypts invoices.",
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 4, "endLine": 4,
                                 "documentVersionId": null, "chunk": null, "quote": "class LoanController"}],
                   "risk": null, "component": null}
                 ], "unsupported": [], "clarifications": []}""".formatted(AnalysisScenario.CONTROLLER));
    }

    @AfterEach
    void tearDown() {
        fake.reset();
    }

    private Enrolled reviewer(String tenant) {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("reviewer"), AnalysisScenario.PASSWORD);
        StaffFixtures.assign(member.email(), tenant);
        StaffFixtures.grant(member.email(), StaffCapability.BUSINESS_REVIEWER);
        return member;
    }

    /** A requirement driven to INTERNAL_REVIEW with BRD v1. */
    private AnalysisScenario inReview() {
        Enrolled registrar = AnalysisScenario.registrar();
        AnalysisScenario s = AnalysisScenario.prepare(this, loggedIn("acme", "alice@acme.test"),
                loggedIn("acme", "aaron@acme.test"), AnalysisScenario.staffSession(client(), registrar, "acme", 0),
                registrar, null);
        s.submit();
        s.drive();
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        return s;
    }

    private Enrolled releaseManager(String tenant) {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("release"), AnalysisScenario.PASSWORD);
        StaffFixtures.assign(member.email(), tenant);
        StaffFixtures.grant(member.email(), StaffCapability.RELEASE_APPROVE);
        return member;
    }

    /** O-16: separation of duties, mandatory gates, binding to hash and commit; no automatic release. */
    @Test
    void releaseNeedsAReleaseManagerAndPassingGates() {
        AnalysisScenario s = inReview();
        Enrolled reviewerMember = reviewer("acme");
        ApiClient reviewer = AnalysisScenario.staffSession(client(), reviewerMember, "acme", 0);
        Map<String, Object> v1 = openVersion(s);
        String versionId = (String) v1.get("id");
        reviewer.post("/api/v1/staff/brd-versions/" + versionId + "/flags/" + flaggedKey(versionId) + "/acknowledge",
                Map.of());
        assertThat(approve(reviewer, versionId, v1.get("content_hash")).status()).isEqualTo(200);
        s.drive();
        String commit = s.repo.headSha();
        Map<String, Object> body = Map.of("expectedContentHash", v1.get("content_hash"), "expectedCommitSha", commit,
                "reason", "Release after review of the gates.");

        // The scans of the pinned snapshot ran: the built-in secret scan completed; others are honestly UNAVAILABLE.
        assertThat(admin.queryForList("""
                SELECT r.kind || ':' || r.status FROM app.security_scan_run r JOIN app.analysis_input_set i
                  ON i.snapshot_id = r.snapshot_id JOIN app.brd_version v ON v.input_set_id = i.id WHERE v.id = ?::uuid
                """, String.class, versionId)).containsExactlyInAnyOrder("SECRET:COMPLETED", "DEPENDENCY:UNAVAILABLE",
                "SAST:UNAVAILABLE");
        // Without RELEASE_APPROVE: 403. The internal approver, even with the capability: separation of duties.
        assertThat(reviewer.post("/api/v1/staff/brd-versions/" + versionId + "/release", body).status()).isEqualTo(403);
        StaffFixtures.grant(reviewerMember.email(), StaffCapability.RELEASE_APPROVE);
        assertThat(reviewer.post("/api/v1/staff/brd-versions/" + versionId + "/release", body).errorCode())
                .isEqualTo("SEPARATION_OF_DUTIES");
        ApiClient manager = AnalysisScenario.staffSession(client(), releaseManager("acme"), "acme", 0);
        // Bound to the exact content hash and commit.
        assertThat(manager.post("/api/v1/staff/brd-versions/" + versionId + "/release", Map.of(
                "expectedContentHash", "0".repeat(64), "expectedCommitSha", commit,
                "reason", "Release after review of the gates.")).errorCode()).isEqualTo("VERSION_CHANGED");
        assertThat(manager.post("/api/v1/staff/brd-versions/" + versionId + "/release", Map.of(
                "expectedContentHash", v1.get("content_hash"), "expectedCommitSha", "f".repeat(40),
                "reason", "Release after review of the gates.")).errorCode()).isEqualTo("VERSION_CHANGED");
        // A HIGH secret finding in the pinned snapshot blocks the release (recorded as a scanner result).
        admin.update("""
                INSERT INTO app.security_finding (tenant_id, scan_run_id, severity, rule_id, category, path, line, evidence)
                SELECT r.tenant_id, r.id, 'HIGH', 'aws-access-key-id', 'SECRET', 'config.yml', 3, 'AKIA…(20 chars)'
                FROM app.security_scan_run r JOIN app.analysis_input_set i ON i.snapshot_id = r.snapshot_id
                JOIN app.brd_version v ON v.input_set_id = i.id WHERE v.id = ?::uuid AND r.kind = 'SECRET'
                """, UUID.fromString(versionId));
        Response blocked = manager.post("/api/v1/staff/brd-versions/" + versionId + "/release", body);
        assertThat(blocked.errorCode()).isEqualTo("RELEASE_GATE_FAILED");
        assertThat(blocked.body().toString()).contains("SECURITY_SCAN_SECRET");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.release_approval WHERE brd_version_id = ?::uuid",
                Integer.class, versionId)).isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.audit_event WHERE action = 'RELEASE_GATE_FAILED' AND resource_id = ?::uuid
                """, Integer.class, versionId)).isPositive();
        s.drive();
        assertThat(s.state()).isEqualTo("INTERNAL_APPROVED");
        // The database refuses a release approval by the internal approver even without the API.
        UUID reviewerId = reviewerMember.id();
        assertThat(catchThrowable(() -> admin.update("""
                INSERT INTO app.release_approval (tenant_id, brd_version_id, requirement_id, release_label, content_hash,
                    commit_sha, approver_staff_id, reason, evidence)
                SELECT tenant_id, id, requirement_id, 'brd-v1', content_hash, ?, ?, 'direct insert test', '[]'::jsonb
                FROM app.brd_version WHERE id = ?::uuid
                """, commit, reviewerId, versionId))).hasMessageContaining("internal approver");
    }

    private Map<String, Object> openVersion(AnalysisScenario s) {
        return admin.queryForMap("""
                SELECT id::text AS id, content_hash, version_no FROM app.brd_version
                WHERE requirement_id = ?::uuid AND status = 'IN_REVIEW'
                """, s.requirementId);
    }

    private String flaggedKey(String versionId) {
        return admin.queryForObject("""
                SELECT item_key FROM app.brd_item WHERE brd_version_id = ?::uuid AND support_state = 'FLAGGED'
                """, String.class, versionId);
    }

    private Response approve(ApiClient reviewer, String versionId, Object hash) {
        return reviewer.post("/api/v1/staff/brd-versions/" + versionId + "/approve", Map.of("expectedContentHash", hash));
    }

    @Test
    void approveReleaseClientViewChangesAndNewVersion() {
        AnalysisScenario s = inReview();
        ApiClient reviewer = AnalysisScenario.staffSession(client(), reviewer("acme"), "acme", 0);
        Map<String, Object> v1 = openVersion(s);
        String versionId = (String) v1.get("id");

        Response queue = reviewer.get("/api/v1/staff/reviews");
        assertThat(queue.status()).isEqualTo(200);
        assertThat(queue.body().valueStream().map(n -> n.path("brdVersionId").asString())).contains(versionId);
        Response detail = reviewer.get("/api/v1/staff/brd-versions/" + versionId);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().path("version").path("items").path(0).path("evidence").path(0).path("quote").asString())
                .contains("/loans");
        assertThat(detail.body().path("requirement").path("title").asString()).isNotBlank();

        // Not released yet: the client gets 404 (AC-CORE-08).
        assertThat(s.alice.get("/api/v1/requirements/" + s.requirementId + "/brd").status()).isEqualTo(404);

        // Approval is bound to the reviewed content and needs every flag acknowledged.
        assertThat(approve(reviewer, versionId, "0".repeat(64)).errorCode()).isEqualTo("VERSION_CHANGED");
        assertThat(approve(reviewer, versionId, v1.get("content_hash")).errorCode()).isEqualTo("FLAGS_NOT_ACKNOWLEDGED");
        assertThat(reviewer.post("/api/v1/staff/brd-versions/" + versionId + "/flags/" + flaggedKey(versionId)
                + "/acknowledge", Map.of("note", "Checked: wording only")).status()).isEqualTo(200);
        Response approved = approve(reviewer, versionId, v1.get("content_hash"));
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        assertThat(approved.body().path("state").asString()).isEqualTo("INTERNAL_APPROVED");
        assertThat(approve(reviewer, versionId, v1.get("content_hash")).errorCode()).isEqualTo("APPROVAL_CONFLICT");
        assertThat(admin.queryForMap("""
                SELECT content_hash, input_set_hash, status, decision FROM app.approval WHERE brd_version_id = ?::uuid
                """, versionId)).containsEntry("content_hash", v1.get("content_hash")).containsEntry("status", "VALID")
                .containsEntry("decision", "APPROVED");

        // O-16 MANUAL: internal approval alone never releases.
        s.drive();
        assertThat(s.state()).isEqualTo("INTERNAL_APPROVED");
        assertThat(s.alice.get("/api/v1/requirements/" + s.requirementId + "/brd").status()).isEqualTo(404);
        // A Release Manager approves the release (bound to hash and commit); the worker executes T25 separately.
        ApiClient manager = AnalysisScenario.staffSession(client(), releaseManager("acme"), "acme", 0);
        Response pending = manager.get("/api/v1/staff/releases");
        assertThat(pending.status()).isEqualTo(200);
        var entry = pending.body().valueStream().filter(n -> n.path("brdVersionId").asString().equals(versionId))
                .findFirst().orElseThrow();
        Response releaseApproved = manager.post("/api/v1/staff/brd-versions/" + versionId + "/release", Map.of(
                "expectedContentHash", entry.path("contentHash").asString(),
                "expectedCommitSha", entry.path("commitSha").asString(),
                "reason", "All gates passed; client may see v1."));
        assertThat(releaseApproved.status()).as(releaseApproved.body().toString()).isEqualTo(200);
        assertThat(admin.queryForObject("SELECT status FROM app.release_approval WHERE brd_version_id = ?::uuid",
                String.class, versionId)).isEqualTo("APPROVED");
        s.drive();
        assertThat(s.state()).isEqualTo("SHARED_WITH_CLIENT");
        assertThat(admin.queryForMap("""
                SELECT status, commit_sha, release_label FROM app.release_approval WHERE brd_version_id = ?::uuid
                """, versionId)).containsEntry("status", "EXECUTED").containsEntry("commit_sha", s.repo.headSha())
                .containsEntry("release_label", "brd-v1");
        Response released = s.alice.get("/api/v1/requirements/" + s.requirementId + "/brd");
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.body().path("version").path("status").asString()).isEqualTo("RELEASED");
        assertThat(released.body().path("version").path("items").path(0).path("evidence").path(0).path("path").asString())
                .isEqualTo(AnalysisScenario.CONTROLLER);
        assertThat(released.body().toString()).doesNotContain("RequestMapping(\\\"/loans\\\")")
                .doesNotContain("\"quote\":\"");
        // GD-05: the project's Approver reads the released BRD; a member of the organisation outside the project
        // cannot (404).
        assertThat(loggedIn("acme", "aaron@acme.test").get("/api/v1/requirements/" + s.requirementId + "/brd").status())
                .isEqualTo(200);
        assertThat(loggedIn("acme", io.github.tony079.sdlc.support.RequirementFixtures.newMember("acme", "brd"))
                .get("/api/v1/requirements/" + s.requirementId + "/brd").status()).isEqualTo(404);
        Response notifications = s.alice.get("/api/v1/notifications");
        assertThat(notifications.body().valueStream().filter(n -> n.path("requirementId").asString()
                .equals(s.requirementId)).map(n -> n.path("kind").asString())).contains("BRD_RELEASED");
        String notificationId = notifications.body().path(0).path("id").asString();
        assertThat(s.alice.post("/api/v1/notifications/" + notificationId + "/read", null).status()).isEqualTo(204);

        // C-09: a formal change request. Submission alone changes nothing.
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        Response change = s.alice.post("/api/v1/requirements/" + s.requirementId + "/change-requests", Map.of(
                "requestedChange", "Please also cover refunds of repayments.",
                "justification", "Refunds are booked against repayments today.",
                "expectedImpact", "Small extension of the repayment API.",
                "proposed", Map.of("acceptanceCriteria", "Metadata visible on the repayment and refund APIs.",
                        "constraints", "Refund data must be encrypted at rest.")));
        assertThat(change.status()).as(change.body().toString()).isEqualTo(201);
        String cr = change.body().path("id").asString();
        assertThat(s.state()).isEqualTo("SHARED_WITH_CLIENT");
        // C-16: structured diff with priorities against the released scope (baseline v1).
        assertThat(change.body().path("baselineVersion").asInt()).isEqualTo(1);
        Map<String, String> priorities = new java.util.HashMap<>();
        change.body().path("diff").forEach(d -> priorities.put(d.path("field").asString(),
                d.path("area").asString() + "/" + d.path("changeType").asString() + "/" + d.path("priority").asString()));
        assertThat(priorities).containsEntry("acceptanceCriteria", "ACCEPTANCE_CRITERIA/MODIFICATION/HIGH")
                .containsEntry("constraints", "SECURITY/MODIFICATION/CRITICAL").hasSize(2);
        // The Client Approver sees nothing before review; cannot decide early; the owner cannot decide.
        assertThat(aaron.get("/api/v1/change-requests").body().valueStream()
                .noneMatch(n -> n.path("id").asString().equals(cr))).isTrue();
        assertThat(s.alice.post("/api/v1/change-requests/" + cr + "/decision", Map.of("decision", "ACCEPT")).status())
                .isEqualTo(403);
        // Business Analyst assessment, priority adjustment with justification, review by a different person.
        Enrolled analystMember = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("analyst"), AnalysisScenario.PASSWORD);
        StaffFixtures.assign(analystMember.email(), "acme");
        StaffFixtures.grant(analystMember.email(), StaffCapability.BUSINESS_ANALYST);
        StaffFixtures.grant(analystMember.email(), StaffCapability.BUSINESS_REVIEWER);
        ApiClient analyst = AnalysisScenario.staffSession(client(), analystMember, "acme", 0);
        assertThat(reviewer.post("/api/v1/staff/change-requests/" + cr + "/assessment", Map.of(
                "scopeAssessment", "Adds refunds to the repayment scope.", "effortEstimate", "3 days",
                "timelineImpact", "One sprint", "technicalImpact", "New endpoint")).status()).isEqualTo(403);
        assertThat(analyst.post("/api/v1/staff/change-requests/" + cr + "/assessment", Map.of(
                "scopeAssessment", "Adds refunds to the repayment scope.", "effortEstimate", "3 days",
                "timelineImpact", "One sprint", "technicalImpact", "New endpoint on the loan service.")).status())
                .isEqualTo(200);
        assertThat(analyst.post("/api/v1/staff/change-requests/" + cr + "/diff/acceptanceCriteria/priority",
                Map.of("priority", "MEDIUM", "reason", "")).status()).isEqualTo(422);
        Response adjusted = analyst.post("/api/v1/staff/change-requests/" + cr + "/diff/acceptanceCriteria/priority",
                Map.of("priority", "MEDIUM", "reason", "Wording only; same behaviour for refunds."));
        assertThat(adjusted.body().toString()).contains("Wording only");
        assertThat(analyst.post("/api/v1/staff/change-requests/" + cr + "/review", Map.of("decision", "APPROVE"))
                .errorCode()).isEqualTo("SEPARATION_OF_DUTIES");
        assertThat(reviewer.post("/api/v1/staff/change-requests/" + cr + "/review", Map.of("decision", "APPROVE"))
                .status()).isEqualTo(200);
        // The project's Approver sees the change request (and, under GD-05, the requirement) and accepts it.
        assertThat(aaron.get("/api/v1/change-requests").body().valueStream()
                .anyMatch(n -> n.path("id").asString().equals(cr))).isTrue();
        assertThat(aaron.get("/api/v1/requirements/" + s.requirementId).status()).isEqualTo(200);
        Response accepted = aaron.post("/api/v1/change-requests/" + cr + "/decision",
                Map.of("decision", "ACCEPT", "note", "Agreed with finance."));
        assertThat(accepted.status()).as(accepted.body().toString()).isEqualTo(200);
        assertThat(accepted.body().path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(admin.queryForList("""
                SELECT version_no || ':' || source FROM app.approved_scope_version WHERE requirement_id = ?::uuid
                ORDER BY version_no""", String.class, UUID.fromString(s.requirementId)))
                .containsExactly("1:RELEASED_BASELINE", "2:CHANGE_REQUEST");
        assertThat(admin.queryForObject("""
                SELECT acceptance_criteria FROM app.requirement_revision WHERE requirement_id = ?::uuid
                ORDER BY revision_no DESC LIMIT 1""", String.class, UUID.fromString(s.requirementId)))
                .contains("refund");
        assertThat(s.state()).isEqualTo("CLIENT_CHANGES_REQUESTED");
        // T27 then T28: a new analysis that produces v2; v1 is superseded and its approval invalidated.
        s.drive();
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        assertThat(openVersion(s).get("version_no")).isEqualTo(2);
        assertThat(admin.queryForObject("SELECT status FROM app.brd_version WHERE id = ?::uuid", String.class, versionId))
                .isEqualTo("SUPERSEDED");
        assertThat(admin.queryForObject("SELECT status FROM app.approval WHERE brd_version_id = ?::uuid", String.class,
                versionId)).isEqualTo("INVALIDATED");
        assertThat(admin.queryForObject("""
                SELECT corrections::text FROM app.analysis_input_set WHERE requirement_id = ?::uuid
                ORDER BY created_at DESC LIMIT 1
                """, String.class, s.requirementId)).contains("refunds").contains("CLIENT");
        assertThat(admin.queryForList("""
                SELECT t.transition FROM app.workflow_transition t JOIN app.workflow_instance i ON i.id = t.instance_id
                WHERE i.requirement_id = ?::uuid ORDER BY t.seq
                """, String.class, s.requirementId)).containsSubsequence("T20_QUEUE_REVIEW", "T21_APPROVE_INTERNAL",
                "T25_RELEASE_TO_CLIENT", "T27_CLIENT_CHANGES", "T28_REANALYSE_CLIENT_CHANGES", "T18_BRD_DRAFTED",
                "T20_QUEUE_REVIEW");
        assertThat(admin.queryForObject("""
                SELECT actor_type FROM app.workflow_transition t JOIN app.workflow_instance i ON i.id = t.instance_id
                WHERE i.requirement_id = ?::uuid AND t.transition = 'T21_APPROVE_INTERNAL'
                """, String.class, s.requirementId)).isEqualTo("STAFF");
    }

    @Test
    void revisionRequestLeadsToANewVersionThatItsAuthorCannotApprove() {
        AnalysisScenario s = inReview();
        Enrolled authorMember = reviewer("acme");
        ApiClient author = AnalysisScenario.staffSession(client(), authorMember, "acme", 0);
        String v1 = (String) openVersion(s).get("id");
        Response revision = author.post("/api/v1/staff/brd-versions/" + v1 + "/request-revision",
                Map.of("notes", List.of(Map.of("itemKey", flaggedKey(v1), "instruction", "Remove the billing claim."))));
        assertThat(revision.body().path("state").asString()).isEqualTo("REVISION_REQUESTED");
        // The revised run drops the billing claim and adds a new (also flagged) statement.
        fake.cannedReply("ba.phase-b", """
                {"items": [
                  {"kind": "FACT", "section": "FACTS", "statement": "Loans are served by the controller mapped to /loans.",
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 3, "endLine": 6,
                                 "documentVersionId": null, "chunk": null, "quote": "RequestMapping(\\"/loans\\")"}],
                   "risk": null, "component": "Loan API"},
                  {"kind": "FACT", "section": "FACTS", "statement": "Repayment dates are kept in the treasury ledger.",
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 4, "endLine": 4,
                                 "documentVersionId": null, "chunk": null, "quote": "class LoanController"}],
                   "risk": null, "component": null}
                 ], "unsupported": [], "clarifications": []}""".formatted(AnalysisScenario.CONTROLLER));
        s.drive();
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        Map<String, Object> v2 = openVersion(s);
        assertThat(v2.get("version_no")).isEqualTo(2);
        String v2Id = (String) v2.get("id");

        assertThat(author.get("/api/v1/staff/brd-versions/" + v2Id).body().path("versions").size()).isEqualTo(2);
        // Version comparison (TDD 16.8, FR-UI-02): statement level by item_key, reported oldest to newest.
        Response diff = author.get("/api/v1/staff/brd-versions/" + v2Id + "/diff?against=" + v1);
        assertThat(diff.status()).as(diff.body().toString()).isEqualTo(200);
        assertThat(diff.body().path("from").path("versionNo").asInt()).isEqualTo(1);
        assertThat(diff.body().path("to").path("versionNo").asInt()).isEqualTo(2);
        assertThat(diff.body().path("removed").path(0).path("statement").asString()).contains("billing");
        assertThat(diff.body().path("added").path(0).path("statement").asString()).contains("treasury ledger");
        assertThat(diff.body().path("removed").size()).isEqualTo(1);
        assertThat(diff.body().path("added").size()).isEqualTo(1);
        assertThat(diff.body().path("unchanged").asInt()).isEqualTo(1);
        assertThat(diff.body().path("changed").size()).isZero();
        assertThat(author.get("/api/v1/staff/brd-versions/" + v2Id + "/diff?against=" + UUID.randomUUID()).status())
                .isEqualTo(404);
        assertThat(s.alice.get("/api/v1/staff/brd-versions/" + v2Id + "/diff?against=" + v1).status())
                .isIn(401, 403);
        assertThat(fake.calls().get(fake.calls().size() - 1).data()).anySatisfy(block ->
                assertThat(block.content()).contains("Remove the billing claim."));

        author.post("/api/v1/staff/brd-versions/" + v2Id + "/flags/" + flaggedKey(v2Id) + "/acknowledge", Map.of());
        Response self = approve(author, v2Id, v2.get("content_hash"));
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.errorCode()).isEqualTo("SELF_APPROVAL_FORBIDDEN");

        ApiClient other = AnalysisScenario.staffSession(client(), reviewer("acme"), "acme", 0);
        assertThat(approve(other, v2Id, v2.get("content_hash")).status()).isEqualTo(200);
    }

    @Test
    void rejectionIsTerminalAndWithdrawsTheVersion() {
        AnalysisScenario s = inReview();
        ApiClient reviewer = AnalysisScenario.staffSession(client(), reviewer("acme"), "acme", 0);
        String v1 = (String) openVersion(s).get("id");
        assertThat(reviewer.post("/api/v1/staff/brd-versions/" + v1 + "/reject", Map.of("reason", "short")).status())
                .isEqualTo(422);
        Response rejected = reviewer.post("/api/v1/staff/brd-versions/" + v1 + "/reject",
                Map.of("reason", "The requirement duplicates REQ-000001."));
        assertThat(rejected.body().path("state").asString()).isEqualTo("REJECTED");
        assertThat(admin.queryForObject("SELECT status FROM app.brd_version WHERE id = ?::uuid", String.class, v1))
                .isEqualTo("WITHDRAWN");
        assertThat(s.alice.get("/api/v1/requirements/" + s.requirementId + "/brd").status()).isEqualTo(404);
    }

    @Test
    void permissionsTenantIsolationAndDatabaseRules() throws Exception {
        AnalysisScenario s = inReview();
        String v1 = (String) openVersion(s).get("id");
        // A registrar without BUSINESS_REVIEWER, a client user, and a Globex reviewer all fail.
        ApiClient registrar = AnalysisScenario.staffSession(client(), AnalysisScenario.registrar(), "acme", 0);
        assertThat(registrar.get("/api/v1/staff/reviews").status()).isEqualTo(403);
        assertThat(s.alice.get("/api/v1/staff/reviews").status()).isEqualTo(403);
        ApiClient globex = AnalysisScenario.staffSession(client(), reviewer("globex"), "globex", 0);
        assertThat(globex.get("/api/v1/staff/brd-versions/" + v1).status()).isEqualTo(404);
        assertThat(globex.get("/api/v1/staff/reviews").body().valueStream()
                .map(n -> n.path("brdVersionId").asString())).doesNotContain(v1);

        // Approvals and BRD content cannot be altered by the API login.
        try (Connection app = db.appConnection(); Statement statement = app.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id', '" + s.tenant + "', false)");
            for (String sql : List.of("UPDATE app.brd_item SET statement = 'x'",
                    "UPDATE app.brd_version SET content_hash = repeat('0', 64)",
                    "DELETE FROM app.approval", "UPDATE app.approval SET decision = 'APPROVED'",
                    "INSERT INTO app.brd_version (tenant_id, brd_id, requirement_id, version_no, pending_result_id, "
                            + "input_set_id, input_hash, content_hash, status, model_provider, model_id) VALUES "
                            + "(gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 9, gen_random_uuid(), "
                            + "gen_random_uuid(), repeat('a',64), repeat('a',64), 'RELEASED', 'x', 'y')")) {
                assertThat(catchThrowable(() -> statement.execute(sql))).as(sql)
                        .satisfiesAnyOf(t -> assertThat(t).hasMessageContaining("permission denied"),
                                t -> assertThat(t).hasMessageContaining("immutable"));
            }
        }
    }
}
