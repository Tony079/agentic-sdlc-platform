package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

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
 * GD-24: the BRD goes straight to the client. The author accepts flagged statements and confirms; a BRD Approver other
 * than the author approves; the engine then releases it when the automated gates pass. Change requests re-analyse.
 * A NOT_FEASIBLE verdict closes the requirement. No Garuda staff take part.
 */
class ClientReviewIT extends ApiIntegrationTest {

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
    }

    @AfterEach
    void tearDown() {
        fake.reset();
    }

    private void proposal(String verdict) {
        fake.cannedReply("ba.phase-b", """
                {"feasibility": {"verdict": "%2$s", "reasons": ["The loan API exists and can carry the metadata."]},
                 "items": [
                  {"kind": "FACT", "section": "CURRENT_SYSTEM", "statement": "Loans are served by the controller mapped to /loans.",
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 3, "endLine": 6,
                                 "documentVersionId": null, "chunk": null, "quote": "RequestMapping(\\"/loans\\")"}],
                   "risk": null, "component": "Loan API"},
                  {"kind": "FACT", "section": "IMPACTED_COMPONENTS", "statement": "The billing module encrypts invoices.",
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 4, "endLine": 4,
                                 "documentVersionId": null, "chunk": null, "quote": "class LoanController"}],
                   "risk": null, "component": null},
                  {"kind": "RECOMMENDATION", "section": "SCENARIOS", "statement": "A repayment without a channel is rejected.",
                   "evidence": [], "risk": null, "component": null}
                 ], "unsupported": [], "clarifications": []}""".formatted(AnalysisScenario.CONTROLLER, verdict));
    }

    private AnalysisScenario analysed(String verdict) {
        proposal(verdict);
        Enrolled registrar = AnalysisScenario.registrar();
        AnalysisScenario s = AnalysisScenario.prepare(this, loggedIn("acme", "alice@acme.test"),
                loggedIn("acme", "aaron@acme.test"), AnalysisScenario.staffSession(client(), registrar, "acme", 0),
                registrar, null);
        s.submit();
        s.drive();
        return s;
    }

    @Test
    void theAuthorConfirmsABrdApproverApprovesAndTheBrdIsReleasedWithoutStaff() throws Exception {
        AnalysisScenario s = analysed("FEASIBLE_WITH_RISKS");
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        ApiClient alice = s.alice;
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        String brd = "/api/v1/requirements/" + s.requirementId + "/brd";

        // The author sees it at once, with the verdict and the flagged statement to decide on.
        Response view = alice.get(brd);
        assertThat(view.status()).as(view.body().toString()).isEqualTo(200);
        assertThat(view.body().path("review").path("stage").asString()).isEqualTo("AUTHOR_REVIEW");
        assertThat(view.body().path("review").path("feasibility").asString()).isEqualTo("FEASIBLE_WITH_RISKS");
        assertThat(view.body().path("review").path("unacceptedFlags").size()).isEqualTo(1);
        String flagged = view.body().path("review").path("unacceptedFlags").get(0).asString();
        assertThat(view.body().path("review").path("canConfirm").asBoolean()).isFalse();
        assertThat(alice.post(brd + "/confirm", Map.of()).errorCode()).isEqualTo("FLAGS_NOT_ACCEPTED");
        // A BRD Approver cannot approve before the author confirms, and the author never approves.
        assertThat(aaron.post(brd + "/approve", Map.of()).errorCode()).isEqualTo("AUTHOR_CONFIRMATION_REQUIRED");

        // An unapproved BRD is never exported as a file.
        assertThat(alice.download(brd + "/export?format=pdf").status()).isEqualTo(409);
        assertThat(alice.post(brd + "/flags/" + flagged + "/accept", Map.of()).status()).isEqualTo(200);
        Response confirmed = alice.post(brd + "/confirm", Map.of());
        assertThat(confirmed.status()).as(confirmed.body().toString()).isEqualTo(200);
        assertThat(confirmed.body().path("review").path("stage").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(alice.post(brd + "/approve", Map.of()).errorCode()).isEqualTo("BRD_DECISION_NOT_ALLOWED");

        Response approved = aaron.post(brd + "/approve",
                Map.of("expectedContentHash", confirmed.body().path("version").path("contentHash").asString()));
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        assertThat(s.state()).isEqualTo("INTERNAL_APPROVED");
        s.drive();
        assertThat(s.state()).isEqualTo("SHARED_WITH_CLIENT");
        assertThat(alice.get(brd).body().path("review").path("stage").asString()).isEqualTo("APPROVED");

        // The approved BRD downloads as PDF and Word, with its statements, approval record and fingerprint.
        String hash = alice.get(brd).body().path("version").path("contentHash").asString();
        ApiClient.Download pdf = aaron.download(brd + "/export?format=pdf&tz=Asia/Kolkata");
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.header("Content-Type")).startsWith("application/pdf");
        assertThat(pdf.header("Content-Disposition")).contains("-BRD-v1.pdf");
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf.body())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(text).contains("Loans are served by the controller mapped to /loans.")
                    .contains("Feasibility: Feasible with risks").contains("Approval record").contains("Page 1 of");
            assertThat(text.replace(" ", "")).contains(hash);
        }
        ApiClient.Download word = alice.download(brd + "/export?format=docx");
        assertThat(word.status()).isEqualTo(200);
        assertThat(word.header("Content-Disposition")).contains("-BRD-v1.docx");
        String documentXml = null;
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(word.body()))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("word/document.xml")) {
                    documentXml = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(documentXml).isNotNull().contains("A repayment without a channel is rejected.").contains(hash);
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(documentXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(alice.download(brd + "/export?format=exe").status()).isEqualTo(422);
        assertThat(admin.queryForObject("SELECT count(*) FROM app.audit_event WHERE action = 'BRD_EXPORTED'",
                Integer.class)).isGreaterThanOrEqualTo(2);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.workflow_transition t JOIN app.workflow_instance i ON i.id = t.instance_id
                WHERE i.requirement_id = ?::uuid AND t.actor_type = 'STAFF'
                """, Integer.class, s.requirementId)).isZero();
    }

    @Test
    void changeRequestsReanalyseAndANotFeasibleVerdictClosesTheRequirement() {
        AnalysisScenario s = analysed("FEASIBLE");
        String brd = "/api/v1/requirements/" + s.requirementId + "/brd";
        assertThat(s.alice.post(brd + "/request-changes", Map.of("text", "short")).status()).isEqualTo(422);
        Response requested = s.alice.post(brd + "/request-changes",
                Map.of("text", "Also cover refunds of repayments."));
        assertThat(requested.status()).as(requested.body().toString()).isEqualTo(200);
        s.drive();
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        Response second = s.alice.get(brd);
        assertThat(second.body().path("version").path("versionNo").asInt()).isEqualTo(2);
        assertThat(second.body().path("review").path("revisionsUsed").asInt()).isEqualTo(1);
        assertThat(second.body().path("review").path("revisionsIncluded").asInt()).isEqualTo(3);

        // Not feasible: closed, the author still sees why.
        AnalysisScenario closed = analysed("NOT_FEASIBLE");
        assertThat(closed.state()).isEqualTo("REJECTED");
        Response view = closed.alice.get("/api/v1/requirements/" + closed.requirementId + "/brd");
        assertThat(view.body().path("review").path("stage").asString()).isEqualTo("CLOSED_NOT_FEASIBLE");
        assertThat(view.body().path("review").path("canConfirm").asBoolean()).isFalse();
    }
}
