package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.modelgateway.internal.FakeModelProvider;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;
import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GD-26: an approved BRD moves to the Technical design stage. The author (or a BRD Approver) starts it; the Architect
 * agent writes the design from the BRD and the code; the engine verifies citations, keeps only real BRD references and
 * computes coverage; the author confirms and a BRD Approver other than the author approves; the design downloads.
 */
class DesignIT extends ApiIntegrationTest {

    private final JdbcTemplate admin = TestDatabase.get().admin();
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
                {"feasibility": {"verdict": "FEASIBLE", "reasons": ["The loan API exists."]},
                 "items": [
                  {"kind": "FACT", "section": "CURRENT_SYSTEM", "statement": "Loans are served by the controller mapped to /loans.",
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 3, "endLine": 6,
                                 "documentVersionId": null, "chunk": null, "quote": "RequestMapping(\\"/loans\\")"}],
                   "risk": null, "component": "Loan API"},
                  {"kind": "RECOMMENDATION", "section": "SCENARIOS", "statement": "A repayment without a channel is rejected.",
                   "evidence": [], "risk": null, "component": null},
                  {"kind": "RECOMMENDATION", "section": "ACCEPTANCE_CRITERIA", "statement": "The repayment API returns the channel.",
                   "evidence": [], "risk": null, "component": null}
                 ], "unsupported": [], "clarifications": []}""".formatted(AnalysisScenario.CONTROLLER));
    }

    @AfterEach
    void tearDown() {
        fake.reset();
    }

    /** A requirement whose BRD is approved and released. */
    private AnalysisScenario approvedBrdWithoutTechnicalApprover() {
        Enrolled registrar = AnalysisScenario.registrar();
        AnalysisScenario s = AnalysisScenario.prepare(this, loggedIn("acme", "alice@acme.test"),
                loggedIn("acme", "aaron@acme.test"), AnalysisScenario.staffSession(client(), registrar, "acme", 0),
                registrar, null);
        s.submit();
        s.drive();
        String brd = "/api/v1/requirements/" + s.requirementId + "/brd";
        Response confirmed = s.alice.post(brd + "/confirm", Map.of());
        assertThat(confirmed.status()).as(confirmed.body().toString()).isEqualTo(200);
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        assertThat(aaron.post(brd + "/approve", Map.of()).status()).isEqualTo(200);
        s.drive();
        assertThat(s.state()).isEqualTo("SHARED_WITH_CLIENT");
        return s;
    }

    private String brdKey(AnalysisScenario s, String section) {
        for (JsonNode item : s.alice.get("/api/v1/requirements/" + s.requirementId + "/brd").body().path("version").path("items")) {
            if (section.equals(item.path("section").asString())) {
                return item.path("itemKey").asString();
            }
        }
        throw new AssertionError("no BRD item in " + section);
    }

    private void designReply(String scenarioKey) {
        fake.cannedReply("arch.files", """
                {"approach": "Extend the loan controller with a channel field.", "relevantPaths": ["%s", "not/a/real/path"]}"""
                .formatted(AnalysisScenario.CONTROLLER));
        fake.cannedReply("arch.design", """
                {"summary": "Add a channel to repayments in the loan controller and reject repayments without one.",
                 "items": [
                  {"section": "API_CHANGES", "kind": "CHANGE", "title": "Validate the repayment channel",
                   "detail": "POST /loans/{id}/repayments rejects a request without channel with 422.",
                   "component": "Loan API", "brdRefs": ["%2$s", "made-up-key"],
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 3, "endLine": 6,
                                 "documentVersionId": null, "chunk": null, "quote": "RequestMapping(\\"/loans\\")"}]},
                  {"section": "DATA_MODEL", "kind": "NEW", "title": "Add repayment.channel",
                   "detail": "New column channel text NOT NULL with a check of allowed values.",
                   "component": null, "brdRefs": [],
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 1, "endLine": 1,
                                 "documentVersionId": null, "chunk": null, "quote": "this text is not in the file"}]},
                  {"section": "TEST_PLAN", "kind": "TASK", "title": "Integration test for a missing channel",
                   "detail": "Posting a repayment without channel returns 422.", "component": null,
                   "brdRefs": ["%2$s"], "evidence": []},
                  {"section": "SECURITY", "kind": "DECISION", "title": "Encrypt uploaded files at rest",
                   "detail": "Store uploads with AES-256 server-side encryption.", "component": "Storage",
                   "brdRefs": [],
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 1, "endLine": 1,
                                 "documentVersionId": null, "chunk": null, "quote": "package com.acme;"}]}
                 ], "openQuestions": ["Which channels are allowed?"]}""".formatted(AnalysisScenario.CONTROLLER, scenarioKey));
    }

    /** A new member of the scenario's project with the Technical Approver role; returns a signed-in client. */
    private ApiClient technicalApprover(AnalysisScenario s) {
        String email = io.github.tony079.sdlc.support.RequirementFixtures.newMember("acme", "tech");
        java.util.UUID user = admin.queryForObject("SELECT id FROM app.app_user WHERE email = ?", java.util.UUID.class, email);
        io.github.tony079.sdlc.support.RequirementFixtures.addMember(java.util.UUID.fromString(s.projectId), user,
                "TECHNICAL_APPROVER");
        return loggedIn("acme", email);
    }

    @Test
    void theApprovedBrdIsHandedToTheTechnicalApproverWhoDesignsReviewsAndApprovesIt() throws Exception {
        AnalysisScenario s = approvedBrdWithoutTechnicalApprover();
        String design = "/api/v1/requirements/" + s.requirementId + "/design";
        ApiClient alice = s.alice;
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");

        // No Technical Approver yet: the requirement says so and the Organisation Admin was told.
        JsonNode none = alice.get(design).body();
        assertThat(none.path("review").path("technicalApprovers").asInt()).isZero();
        assertThat(none.path("review").path("blockedReason").asString()).contains("no Technical Approver");
        assertThat(aaron.get("/api/v1/notifications").body().valueStream().map(n -> n.path("kind").asString()))
                .contains("TDD_NO_TECHNICAL_APPROVER");
        // The author and the BRD Approver cannot start it.
        assertThat(alice.post(design + "/start", Map.of()).errorCode()).isEqualTo("DESIGN_DECISION_NOT_ALLOWED");
        assertThat(aaron.post(design + "/start", Map.of()).errorCode()).isEqualTo("DESIGN_DECISION_NOT_ALLOWED");

        ApiClient tina = technicalApprover(s);
        Response before = tina.get(design);
        assertThat(before.body().path("review").path("stage").asString()).isEqualTo("NOT_STARTED");
        assertThat(before.body().path("review").path("canStart").asBoolean()).isTrue();
        assertThat(alice.get(design).body().path("review").path("canStart").asBoolean()).isFalse();

        designReply(brdKey(s, "SCENARIOS"));
        Response started = tina.post(design + "/start", Map.of());
        assertThat(started.status()).as(started.body().toString()).isEqualTo(200);
        assertThat(started.body().path("review").path("stage").asString()).isEqualTo("GENERATING");
        assertThat(tina.post(design + "/start", Map.of()).errorCode()).isEqualTo("DESIGN_IN_PROGRESS");
        s.drive();

        JsonNode view = tina.get(design).body();
        assertThat(view.path("review").path("stage").asString()).as(view.toString()).isEqualTo("IN_REVIEW");
        JsonNode items = view.path("version").path("items");
        assertThat(items.size()).isEqualTo(4);
        assertThat(items.get(0).path("supportState").asString()).isEqualTo("VERIFIED");
        assertThat(items.get(0).path("brdRefs").size()).isEqualTo(1);
        assertThat(items.get(1).path("supportState").asString()).isEqualTo("FLAGGED");
        assertThat(items.get(2).path("supportState").asString()).isEqualTo("NOT_TRACED");
        // A real line that has nothing to do with the item is not evidence: dropped, and the item is not traced.
        assertThat(items.get(3).path("supportState").asString()).isEqualTo("NOT_TRACED");
        assertThat(items.get(3).path("evidence").size()).isZero();
        // The agent was given the coverage checklist and the repository summary.
        var designCall = fake.calls().stream().filter(c -> "arch.design".equals(c.promptId())).reduce((a, b) -> b).orElseThrow();
        assertThat(designCall.promptVersion()).isEqualTo(3);
        assertThat(designCall.data().stream().filter(d -> d.label().startsWith("checklist")).findFirst().orElseThrow()
                .content()).contains(brdKey(s, "SCENARIOS")).contains(brdKey(s, "ACCEPTANCE_CRITERIA"));
        assertThat(designCall.data().stream().anyMatch(d -> d.label().startsWith("repository summary"))).isTrue();
        JsonNode coverage = view.path("version").path("coverage");
        assertThat(coverage.size()).isEqualTo(2);
        assertThat(coverage.valueStream().filter(c -> c.path("covered").asBoolean()).count()).isEqualTo(1);
        assertThat(tina.get("/api/v1/notifications").body().valueStream().map(n -> n.path("kind").asString()))
                .contains("TDD_READY_FOR_REVIEW");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.model_call_record WHERE requirement_id = ?::uuid AND prompt_id LIKE 'arch.%'
                """, Integer.class, s.requirementId)).isEqualTo(2);

        // Only the Technical Approver decides; flags first; approving alone needs the explicit confirmation.
        String flagged = items.get(1).path("itemKey").asString();
        assertThat(alice.post(design + "/flags/" + flagged + "/accept", Map.of()).errorCode())
                .isEqualTo("DESIGN_DECISION_NOT_ALLOWED");
        assertThat(tina.post(design + "/approve", Map.of("confirmApproveAlone", true)).errorCode())
                .isEqualTo("FLAGS_NOT_ACCEPTED");
        assertThat(tina.post(design + "/flags/" + flagged + "/accept", Map.of()).status()).isEqualTo(200);
        assertThat(alice.post(design + "/approve", Map.of("confirmApproveAlone", true)).errorCode())
                .isEqualTo("DESIGN_DECISION_NOT_ALLOWED");
        assertThat(tina.post(design + "/approve", Map.of()).errorCode()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(alice.download(design + "/export?format=pdf").status()).isEqualTo(409);
        Response approved = tina.post(design + "/approve", Map.of("confirmApproveAlone", true,
                "expectedContentHash", view.path("version").path("contentHash").asString()));
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        assertThat(approved.body().path("review").path("stage").asString()).isEqualTo("APPROVED");
        assertThat(approved.body().path("review").path("approvalNote").asString()).contains("single Technical Approver");

        ApiClient.Download pdf = aaron.download(design + "/export?format=pdf");
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.header("Content-Disposition")).contains("-TDD-v1.pdf");
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf.body())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(text).contains("TECHNICAL DESIGN DOCUMENT").contains("Validate the repayment channel")
                    .contains("Coverage of the approved BRD").contains("NOT COVERED")
                    .contains("Approved by the Technical Approver");
        }
        assertThat(alice.download(design + "/export?format=docx").status()).isEqualTo(200);

        assertThatThrownBy(() -> admin.update("UPDATE app.tdd_version SET summary = 'x' WHERE requirement_id = ?::uuid",
                s.requirementId)).hasMessageContaining("immutable");
    }

    @Test
    void theAuthorRequestsChangesAndAFailedDesignIsRetriedByTheTechnicalApprover() {
        AnalysisScenario s = approvedBrdWithoutTechnicalApprover();
        ApiClient tina = technicalApprover(s);
        String design = "/api/v1/requirements/" + s.requirementId + "/design";
        designReply(brdKey(s, "SCENARIOS"));
        tina.post(design + "/start", Map.of());
        s.drive();
        assertThat(s.alice.post(design + "/request-changes", Map.of("text", "short")).status()).isEqualTo(422);
        // The author follows the design and may ask for changes.
        Response changed = s.alice.post(design + "/request-changes", Map.of("text", "Also cover refunds of repayments."));
        assertThat(changed.status()).as(changed.body().toString()).isEqualTo(200);
        s.drive();
        JsonNode view = s.alice.get(design).body();
        assertThat(view.path("version").path("versionNo").asInt()).isEqualTo(2);
        assertThat(view.path("version").path("changeRequest").asString()).isEqualTo("Also cover refunds of repayments.");
        assertThat(view.path("history").size()).isEqualTo(2);

        // The model fails: the design is FAILED, then retried by the Technical Approver (not the author).
        fake.reset();
        fake.cannedReply("arch.files", "not json at all");
        Response again = tina.post(design + "/request-changes", Map.of("text", "Describe the rollback plan as well."));
        assertThat(again.status()).isEqualTo(200);
        for (int i = 0; i < 4; i++) {
            s.drive();
            admin.update("""
                    UPDATE app.job SET available_at = now() WHERE aggregate_id = ?::uuid AND job_type = 'TDD_GENERATION'
                      AND status = 'QUEUED'
                    """, s.requirementId);
        }
        s.drive();
        assertThat(tina.get(design).body().path("review").path("stage").asString()).isEqualTo("FAILED");
        assertThat(s.alice.post(design + "/retry", Map.of()).errorCode()).isEqualTo("DESIGN_DECISION_NOT_ALLOWED");
        designReply(brdKey(s, "SCENARIOS"));
        Response retried = tina.post(design + "/retry", Map.of());
        assertThat(retried.status()).as(retried.body().toString()).isEqualTo(200);
        s.drive();
        assertThat(tina.get(design).body().path("review").path("stage").asString()).isEqualTo("IN_REVIEW");
    }
}
