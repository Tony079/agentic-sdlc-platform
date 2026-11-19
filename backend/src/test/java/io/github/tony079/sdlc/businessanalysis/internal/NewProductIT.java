package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.modelgateway.internal.FakeModelProvider;
import io.github.tony079.sdlc.outbox.internal.OutboxDispatcher;
import io.github.tony079.sdlc.queue.internal.JdbcJobStore;
import io.github.tony079.sdlc.queue.internal.JobWorker;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.RequirementFixtures;
import io.github.tony079.sdlc.support.Samples;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GD-28: a New product requirement is analysed by the agents with no code. No repository or environment is needed;
 * the BA agent works from the requirement and its documents (told it is a new product, Phase A skipped); the BRD is
 * reviewed and approved and released (no code means no code scans); the Architect agent then designs it as a new
 * product.
 */
class NewProductIT extends ApiIntegrationTest {

    private final JdbcTemplate admin = TestDatabase.get().admin();
    private FakeModelProvider fake;

    @BeforeEach
    void setUp() {
        WorkerContext.get();
        fake = WorkerContext.bean(FakeModelProvider.class);
        fake.reset();
        fake.cannedReply("ba.phase-b", """
                {"feasibility": {"verdict": "FEASIBLE", "reasons": ["The scope is clear and needs no integrations."]},
                 "items": [
                  {"kind": "ASSUMPTION", "section": "CONTEXT", "statement": "Candidates want a portfolio site from their resume.",
                   "evidence": [], "risk": null, "component": null},
                  {"kind": "FACT", "section": "CONTEXT", "statement": "The client wants additional repayment metadata stored.",
                   "evidence": [{"type": "REQUIREMENT", "path": "description", "startLine": null, "endLine": null,
                                 "documentVersionId": null, "chunk": null,
                                 "quote": "Store additional repayment metadata"}], "risk": null, "component": null},
                  {"kind": "FACT", "section": "CONTEXT", "statement": "Users sign in with LinkedIn.",
                   "evidence": [{"type": "REQUIREMENT", "path": "description", "startLine": null, "endLine": null,
                                 "documentVersionId": null, "chunk": null,
                                 "quote": "sign in with LinkedIn"}], "risk": null, "component": null},
                  {"kind": "RECOMMENDATION", "section": "SCENARIOS", "statement": "A candidate uploads a resume and reviews the extracted sections.",
                   "evidence": [], "risk": null, "component": null},
                  {"kind": "RECOMMENDATION", "section": "ACCEPTANCE_CRITERIA", "statement": "The site shows every extracted section.",
                   "evidence": [], "risk": null, "component": null}
                 ], "unsupported": [], "clarifications": []}""");
        fake.cannedReply("arch.files", """
                {"approach": "A new web application.", "relevantPaths": []}""");
        fake.cannedReply("arch.design", """
                {"summary": "A Next.js front end with a Spring Boot API and PostgreSQL.",
                 "items": [
                  {"section": "ARCHITECTURE", "kind": "DECISION", "title": "Next.js, Spring Boot and PostgreSQL",
                   "detail": "Next.js 15 front end, Spring Boot 4 API, PostgreSQL 16; Apache PDFBox for parsing.",
                   "component": "Portfolio app", "brdRefs": [], "evidence": []},
                  {"section": "TEST_PLAN", "kind": "TASK", "title": "Section coverage test",
                   "detail": "Test that every extracted section is shown (acceptance-criteria-99f717e05ef9).",
                   "component": null, "brdRefs": [], "evidence": []}
                 ], "openQuestions": []}""");
    }

    @AfterEach
    void tearDown() {
        fake.reset();
    }

    private void drive(UUID tenant) {
        OutboxDispatcher dispatcher = WorkerContext.bean(OutboxDispatcher.class);
        JobWorker worker = WorkerContext.bean(JobWorker.class);
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

    private String state(String requirementId) {
        return admin.queryForObject("SELECT projected_state FROM app.workflow_instance WHERE requirement_id = ?::uuid",
                String.class, requirementId);
    }

    @Test
    void aNewProductIsAnalysedWithoutCodeApprovedAndDesignedFromScratch() {
        UUID tenant = TestDatabase.get().tenantId("acme");
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        String projectId = RequirementFixtures.project(alice);
        Map<String, Object> body = RequirementFixtures.complete(projectId);
        body.put("kind", "NEW_PRODUCT");
        body.remove("repositoryUrl");
        Response created = alice.post("/api/v1/requirements", body);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        String id = created.body().path("id").asString();
        assertThat(alice.upload("/api/v1/requirements/" + id + "/documents", "brief.txt", "text/plain",
                Samples.text("The portfolio site lists experience, projects and skills.")).status()).isEqualTo(201);
        // The first BRD answer breaks a rule (no feasibility reasons); the gateway asks for a repair, which passes.
        fake.enqueue(new FakeModelProvider.Reply("""
                {"feasibility": {"verdict": "FEASIBLE", "reasons": []}, "items": [], "unsupported": [], "clarifications": []}"""));
        Response latest = alice.get("/api/v1/requirements/" + id);
        Response submitted = alice.post("/api/v1/requirements/" + id + "/submit", null,
                RequirementFixtures.ifMatch(latest), true);
        assertThat(submitted.status()).as(submitted.body().toString()).isEqualTo(200);
        drive(tenant);

        // Analysed (not parked in manual intake), from the requirement and its document only.
        assertThat(state(id)).isEqualTo("INTERNAL_REVIEW");
        assertThat(fake.calls().stream().noneMatch(c -> "ba.phase-a".equals(c.promptId()))).isTrue();
        var phaseB = fake.calls().stream().filter(c -> "ba.phase-b".equals(c.promptId())).reduce((a, b) -> b).orElseThrow();
        assertThat(phaseB.promptVersion()).isEqualTo(7);
        assertThat(phaseB.data().stream().filter(d -> d.label().startsWith("product type")).findFirst().orElseThrow()
                .content()).startsWith("NEW_PRODUCT");
        assertThat(phaseB.data().stream().anyMatch(d -> d.label().contains("brief.txt") || d.content().contains("portfolio site lists")))
                .isTrue();
        assertThat(admin.queryForObject("""
                SELECT repository_id IS NULL AND sources = '[]'::jsonb FROM app.analysis_input_set
                WHERE requirement_id = ?::uuid ORDER BY created_at DESC LIMIT 1
                """, Boolean.class, id)).isTrue();

        // GD-29: the timeline shows each step, the repaired answer with the rule it broke, and the result.
        var lines = alice.get("/api/v1/requirements/" + id + "/activity").body();
        java.util.List<String> messages = lines.valueStream().map(l -> l.path("kind").asString() + " " + l.path("message").asString()).toList();
        assertThat(messages).anyMatch(m -> m.startsWith("STEP Started the analysis") && m.contains("new product"));
        assertThat(messages).anyMatch(m -> m.startsWith("STEP Writing the BRD"));
        assertThat(messages).anyMatch(m -> m.startsWith("WARN The model's first answer broke a rule") && m.contains("feasibility.reasons"));
        assertThat(messages).anyMatch(m -> m.startsWith("DONE BRD ready for review: 5 statements, verdict feasible"));

        // GD-30: a quote of the client's requirement verifies as a FACT; an invented one is downgraded.
        String itemsSql = """
                SELECT i.kind || ' ' || i.support_state FROM app.brd_item i JOIN app.brd_version v ON v.id = i.brd_version_id
                JOIN app.brd b ON b.id = v.brd_id WHERE b.requirement_id = ?::uuid AND i.statement LIKE ?
                """;
        assertThat(admin.queryForObject(itemsSql, String.class, id, "The client wants%")).isEqualTo("FACT VERIFIED");
        assertThat(admin.queryForObject(itemsSql, String.class, id, "%LinkedIn%")).startsWith("ASSUMPTION");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.brd_item_evidence e JOIN app.brd_item i ON i.id = e.brd_item_id
                JOIN app.brd_version v ON v.id = i.brd_version_id JOIN app.brd b ON b.id = v.brd_id
                WHERE b.requirement_id = ?::uuid AND e.type = 'REQUIREMENT' AND e.requirement_revision_no IS NOT NULL
                """, Integer.class, id)).isEqualTo(1);
        // Step lines never carry the model's text; draft lines do, labelled, and only for those who may see the BRD.
        assertThat(messages.stream().filter(m -> !m.startsWith("DRAFT")).noneMatch(m -> m.contains("Candidates want")))
                .as("no model text in step lines").isTrue();
        assertThat(messages).contains("DRAFT Scenarios: A candidate uploads a resume and reviews the extracted sections.");
        String viewerEmail = RequirementFixtures.newMember("acme", "viewer");
        UUID viewer = admin.queryForObject("SELECT id FROM app.app_user WHERE email = ?", UUID.class, viewerEmail);
        RequirementFixtures.addMember(UUID.fromString(projectId), viewer, "VIEWER");
        var seenByViewer = loggedIn("acme", viewerEmail).get("/api/v1/requirements/" + id + "/activity").body();
        assertThat(seenByViewer.size()).isPositive();
        assertThat(seenByViewer.valueStream().noneMatch(l -> "DRAFT".equals(l.path("kind").asString()))).isTrue();
        long last = lines.get(lines.size() - 1).path("id").asLong();
        assertThat(alice.get("/api/v1/requirements/" + id + "/activity?after=" + last).body().size()).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.update(
                "DELETE FROM app.analysis_activity WHERE requirement_id = ?::uuid", id)).hasMessageContaining("append-only");

        // BRD review and approval as usual; with no code there is nothing to scan, so it is released.
        String brd = "/api/v1/requirements/" + id + "/brd";
        Response confirmed = alice.post(brd + "/confirm", Map.of());
        assertThat(confirmed.status()).as(confirmed.body().toString()).isEqualTo(200);
        assertThat(aaron.post(brd + "/approve", Map.of()).status()).isEqualTo(200);
        drive(tenant);
        assertThat(state(id)).isEqualTo("SHARED_WITH_CLIENT");

        // The Architect agent designs it as a new product.
        String email = RequirementFixtures.newMember("acme", "tech");
        UUID tech = admin.queryForObject("SELECT id FROM app.app_user WHERE email = ?", UUID.class, email);
        RequirementFixtures.addMember(UUID.fromString(projectId), tech, "TECHNICAL_APPROVER");
        ApiClient tina = loggedIn("acme", email);
        String design = "/api/v1/requirements/" + id + "/design";
        assertThat(tina.post(design + "/start", Map.of()).status()).isEqualTo(200);
        drive(tenant);
        assertThat(tina.get(design).body().path("review").path("stage").asString()).isEqualTo("IN_REVIEW");
        assertThat(alice.get("/api/v1/requirements/" + id + "/activity").body().valueStream()
                .map(l -> l.path("stage").asString() + " " + l.path("message").asString()))
                .anyMatch(m -> m.startsWith("DESIGN Design version 1 ready for review"));
        // GD-30: a BRD item key written in the text is shown as a readable reference.
        assertThat(admin.queryForObject("""
                SELECT i.detail FROM app.tdd_item i JOIN app.tdd_version v ON v.id = i.tdd_version_id
                WHERE v.requirement_id = ?::uuid AND i.title = 'Section coverage test'
                """, String.class, id)).isEqualTo("Test that every extracted section is shown (BRD acceptance criteria 1).");
        Response change = alice.post("/api/v1/requirements/" + id + "/client-changes",
                Map.of("text", "Please rewrite the BRD and list each acceptance criterion separately."));
        assertThat(change.status()).isEqualTo(200);
        Response again = alice.post("/api/v1/requirements/" + id + "/client-changes",
                Map.of("text", "Please rewrite the BRD and list each acceptance criterion separately."));
        // Only one change request can be open; the reason is returned for the page to show.
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().path("error").path("code").asString()).isEqualTo("CHANGE_REQUEST_OPEN");
        var designCall = fake.calls().stream().filter(c -> "arch.design".equals(c.promptId())).reduce((a, b) -> b).orElseThrow();
        assertThat(designCall.data().stream().filter(d -> d.label().startsWith("repository summary")).findFirst()
                .orElseThrow().content()).contains("design this as a new product");
    
        // GD-31: the author regenerates the BRD: not a change request, no charge. The design in review is replaced,
        // the open change request withdrawn, and a new BRD version comes back for review.
        String regenerate = "/api/v1/requirements/" + id + "/brd/regenerate";
        assertThat(tina.post(regenerate, Map.of()).status()).isEqualTo(403);
        Response regenerated = alice.post(regenerate, Map.of());
        assertThat(regenerated.status()).as(regenerated.body().toString()).isEqualTo(200);
        assertThat(admin.queryForObject("SELECT status FROM app.tdd_version WHERE requirement_id = ?::uuid "
                + "ORDER BY version_no DESC LIMIT 1", String.class, id)).isEqualTo("SUPERSEDED");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.change_request WHERE requirement_id = ?::uuid "
                + "AND status = 'WITHDRAWN'", Integer.class, id)).isEqualTo(1);
        assertThat(alice.post(regenerate, Map.of()).body().path("error").path("code").asString())
                .isEqualTo("REGENERATE_NOT_AVAILABLE");
        drive(tenant);
        assertThat(state(id)).isEqualTo("INTERNAL_REVIEW");
        assertThat(admin.queryForObject("""
                SELECT max(v.version_no) FROM app.brd_version v JOIN app.brd b ON b.id = v.brd_id
                WHERE b.requirement_id = ?::uuid
                """, Integer.class, id)).isEqualTo(2);
        assertThat(admin.queryForObject("SELECT count(*) FROM app.brd_client_decision WHERE requirement_id = ?::uuid "
                + "AND kind = 'CHANGES_REQUESTED'", Integer.class, id)).as("not counted as a change request").isZero();
    }
}
