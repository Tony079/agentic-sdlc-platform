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
    private AnalysisScenario approvedBrd() {
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
                   "brdRefs": ["%2$s"], "evidence": []}
                 ], "openQuestions": ["Which channels are allowed?"]}""".formatted(AnalysisScenario.CONTROLLER, scenarioKey));
    }

    @Test
    void theApprovedBrdIsDesignedReviewedApprovedAndDownloaded() throws Exception {
        AnalysisScenario s = approvedBrd();
        String design = "/api/v1/requirements/" + s.requirementId + "/design";
        ApiClient alice = s.alice;
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");

        Response before = alice.get(design);
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.body().path("review").path("stage").asString()).isEqualTo("NOT_STARTED");
        assertThat(before.body().path("review").path("canStart").asBoolean()).isTrue();

        designReply(brdKey(s, "SCENARIOS"));
        Response started = alice.post(design + "/start", Map.of());
        assertThat(started.status()).as(started.body().toString()).isEqualTo(200);
        assertThat(started.body().path("review").path("stage").asString()).isEqualTo("GENERATING");
        assertThat(alice.post(design + "/start", Map.of()).errorCode()).isEqualTo("DESIGN_IN_PROGRESS");
        s.drive();

        JsonNode view = alice.get(design).body();
        assertThat(view.path("review").path("stage").asString()).as(view.toString()).isEqualTo("AUTHOR_REVIEW");
        JsonNode items = view.path("version").path("items");
        assertThat(items.size()).isEqualTo(3);
        // Engine checks: the citation verified, the invented quote flagged, the invented BRD key dropped.
        assertThat(items.get(0).path("supportState").asString()).isEqualTo("VERIFIED");
        assertThat(items.get(0).path("brdRefs").size()).isEqualTo(1);
        assertThat(items.get(1).path("supportState").asString()).isEqualTo("FLAGGED");
        assertThat(items.get(2).path("supportState").asString()).isEqualTo("NOT_TRACED");
        // Coverage: the scenario is covered, the acceptance criterion is not.
        JsonNode coverage = view.path("version").path("coverage");
        assertThat(coverage.size()).isEqualTo(2);
        assertThat(coverage.valueStream().filter(c -> c.path("covered").asBoolean()).count()).isEqualTo(1);
        assertThat(view.path("version").path("openQuestions").get(0).asString()).isEqualTo("Which channels are allowed?");
        assertThat(alice.get("/api/v1/notifications").body().valueStream().map(n -> n.path("kind").asString()))
                .contains("TDD_READY_FOR_REVIEW");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.model_call_record WHERE requirement_id = ?::uuid AND prompt_id LIKE 'arch.%'
                """, Integer.class, s.requirementId)).isEqualTo(2);

        // Review: flags first, then the author confirms, then a BRD Approver other than the author approves.
        assertThat(alice.post(design + "/confirm", Map.of()).errorCode()).isEqualTo("FLAGS_NOT_ACCEPTED");
        assertThat(aaron.post(design + "/approve", Map.of()).errorCode()).isEqualTo("AUTHOR_CONFIRMATION_REQUIRED");
        String flagged = items.get(1).path("itemKey").asString();
        assertThat(alice.post(design + "/flags/" + flagged + "/accept", Map.of()).status()).isEqualTo(200);
        Response confirmed = alice.post(design + "/confirm", Map.of());
        assertThat(confirmed.body().path("review").path("stage").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(alice.post(design + "/approve", Map.of()).errorCode()).isEqualTo("DESIGN_DECISION_NOT_ALLOWED");
        assertThat(alice.download(design + "/export?format=pdf").status()).isEqualTo(409);
        Response approved = aaron.post(design + "/approve",
                Map.of("expectedContentHash", confirmed.body().path("version").path("contentHash").asString()));
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        assertThat(approved.body().path("review").path("stage").asString()).isEqualTo("APPROVED");

        ApiClient.Download pdf = aaron.download(design + "/export?format=pdf");
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.header("Content-Disposition")).contains("-TDD-v1.pdf");
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf.body())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(text).contains("TECHNICAL DESIGN DOCUMENT").contains("Validate the repayment channel")
                    .contains("Coverage of the approved BRD").contains("NOT COVERED");
        }
        assertThat(alice.download(design + "/export?format=docx").status()).isEqualTo(200);

        // The database refuses what the API refuses: content of an approved design cannot change.
        assertThatThrownBy(() -> admin.update("UPDATE app.tdd_version SET summary = 'x' WHERE requirement_id = ?::uuid",
                s.requirementId)).hasMessageContaining("immutable");
    }

    @Test
    void changesRequestedProduceANewVersionAndAFailedDesignCanBeRetried() {
        AnalysisScenario s = approvedBrd();
        String design = "/api/v1/requirements/" + s.requirementId + "/design";
        designReply(brdKey(s, "SCENARIOS"));
        s.alice.post(design + "/start", Map.of());
        s.drive();
        assertThat(s.alice.post(design + "/request-changes", Map.of("text", "short")).status()).isEqualTo(422);
        Response changed = s.alice.post(design + "/request-changes", Map.of("text", "Also cover refunds of repayments."));
        assertThat(changed.status()).as(changed.body().toString()).isEqualTo(200);
        s.drive();
        JsonNode view = s.alice.get(design).body();
        assertThat(view.path("version").path("versionNo").asInt()).isEqualTo(2);
        assertThat(view.path("version").path("changeRequest").asString()).isEqualTo("Also cover refunds of repayments.");
        assertThat(view.path("history").size()).isEqualTo(2);

        // The model fails: the design is FAILED, then retried by the author.
        fake.reset();
        fake.cannedReply("arch.files", "not json at all");
        Response again = s.alice.post(design + "/request-changes", Map.of("text", "Describe the rollback plan as well."));
        assertThat(again.status()).isEqualTo(200);
        for (int i = 0; i < 4; i++) {
            s.drive();
            admin.update("""
                    UPDATE app.job SET available_at = now() WHERE aggregate_id = ?::uuid AND job_type = 'TDD_GENERATION'
                      AND status = 'QUEUED'
                    """, s.requirementId);
        }
        s.drive();
        assertThat(s.alice.get(design).body().path("review").path("stage").asString()).isEqualTo("FAILED");
        designReply(brdKey(s, "SCENARIOS"));
        Response retried = s.alice.post(design + "/retry", Map.of());
        assertThat(retried.status()).as(retried.body().toString()).isEqualTo(200);
        s.drive();
        assertThat(s.alice.get(design).body().path("review").path("stage").asString()).isEqualTo("AUTHOR_REVIEW");
    }
}
