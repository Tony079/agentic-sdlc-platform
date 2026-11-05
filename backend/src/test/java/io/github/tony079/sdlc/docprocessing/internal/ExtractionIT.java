package io.github.tony079.sdlc.docprocessing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.document.api.DocumentProcessing;
import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.internal.OutboxDispatcher;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.internal.JdbcJobStore;
import io.github.tony079.sdlc.queue.internal.JobWorker;
import io.github.tony079.sdlc.queue.internal.TestWorkers;
import io.github.tony079.sdlc.storage.api.ObjectStore;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.Pdfs;
import io.github.tony079.sdlc.support.RequirementFixtures;
import io.github.tony079.sdlc.support.Samples;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Milestone 5 end to end (PRD 7A.3, TDD 15.5-15.7): scanned documents are extracted by the real extractor process,
 * the coordinator validates the output, and chunks with positions are stored and searchable. Covers no-OCR outcomes,
 * the MANUAL_INTAKE and O-15 gates, untrusted-output validation, and bounded retries ending in EXTRACTION_FAILED_FINAL.
 */
class ExtractionIT extends ApiIntegrationTest {

    @TempDir
    Path tmp;

    private final JdbcTemplate admin = TestDatabase.get().admin();
    private ApiClient alice;
    private UUID acme;
    private String project;

    @BeforeEach
    void setUp() {
        WorkerContext.get();
        alice = loggedIn("acme", "alice@acme.test");
        acme = TestDatabase.get().tenantId("acme");
        project = RequirementFixtures.project(alice);
    }

    private String requirement(String kind) {
        Map<String, Object> body = RequirementFixtures.complete(project);
        body.put("kind", kind);
        if (kind.equals("NEW_PRODUCT")) {
            body.remove("repositoryUrl");
        }
        return alice.post("/api/v1/requirements", body).body().path("id").asString();
    }

    private String upload(String requirement, String name, byte[] content) {
        Response uploaded = alice.upload("/api/v1/requirements/" + requirement + "/documents", name, null, content);
        assertThat(uploaded.status()).isEqualTo(201);
        return uploaded.body().path("documentId").asString();
    }

    /** Delivers events and runs jobs of this tenant until nothing is left (scan, then extract). */
    private void process(JobWorker worker) {
        OutboxDispatcher dispatcher = WorkerContext.bean(OutboxDispatcher.class);
        for (int round = 0; round < 4; round++) {
            while (dispatcher.dispatchNext(acme) != OutboxDispatcher.Outcome.NONE) {
                // drain
            }
            for (JdbcJobStore.Claim claim : worker.claim(acme, 20)) {
                worker.process(claim);
            }
        }
    }

    private String state(String documentId) {
        return admin.queryForObject("SELECT state FROM app.document_version WHERE document_id = ?::uuid", String.class,
                documentId);
    }

    @Test
    void scannedDocumentsAreExtractedIntoSearchableChunks() {
        String req = requirement("EXISTING_REPOSITORY");
        String md = upload(req, "rules.md", Samples.text("# Repayment rules\nEvery repayment carries a channel code."));
        String pdf = upload(req, "spec.pdf", Pdfs.withText("Interest is computed daily", "Penalty after 30 days"));
        String docx = upload(req, "brd.docx", Samples.docx("Existing BRD mentions reconciliation"));
        String scanned = upload(req, "scan.pdf", Pdfs.imageOnly());

        process(WorkerContext.bean(JobWorker.class));

        assertThat(state(md)).isEqualTo("PROCESSED");
        assertThat(state(pdf)).isEqualTo("PROCESSED");
        assertThat(state(docx)).isEqualTo("PROCESSED");
        assertThat(state(scanned)).isEqualTo("UNSUPPORTED_CONTENT");
        assertThat(admin.queryForObject("SELECT reason_code FROM app.document_version WHERE document_id = ?::uuid",
                String.class, scanned)).isEqualTo("NO_TEXT_LAYER");
        assertThat(admin.queryForList("""
                SELECT c.page_no FROM app.document_chunk c JOIN app.document_version v ON v.id = c.version_id
                WHERE v.document_id = ?::uuid ORDER BY c.ordinal
                """, Integer.class, pdf)).containsExactly(1, 2);
        assertThat(admin.queryForObject("""
                SELECT c.label FROM app.document_chunk c JOIN app.document_version v ON v.id = c.version_id
                WHERE v.document_id = ?::uuid
                """, String.class, md)).isEqualTo("Repayment rules");
        // Structured text search (FR-DPR-09).
        assertThat(admin.queryForList("""
                SELECT v.document_id::text FROM app.document_chunk c JOIN app.document_version v ON v.id = c.version_id
                WHERE v.requirement_id = ?::uuid AND c.tsv @@ plainto_tsquery('simple', 'penalty days')
                """, String.class, req)).containsExactly(pdf);
        Map<String, Object> extraction = admin.queryForMap("""
                SELECT e.outcome, e.chunk_count, e.output_sha256 FROM app.document_extraction e
                JOIN app.document_version v ON v.id = e.version_id WHERE v.document_id = ?::uuid
                """, docx);
        assertThat(extraction).containsEntry("outcome", "PROCESSED").containsEntry("chunk_count", 1);
        assertThat(admin.queryForObject("SELECT count(*) FROM app.audit_event WHERE resource_id = ?::uuid "
                + "AND action = 'DOCUMENT_PROCESSED'", Integer.class, md)).isEqualTo(1);
        // A processing outcome never moves the requirement (FR-DPR-10).
        assertThat(alice.get("/api/v1/requirements/" + req).body().path("state").asString()).isEqualTo("DRAFT");
    }

    /** PRD 0.4 / TDD 15.4 (WF-M01): documents of a MANUAL_INTAKE requirement are scanned and stored, not extracted. */
    @Test
    void noExtractionIsScheduledInManualIntake() {
        String req = requirement("NEW_PRODUCT");
        Response detail = alice.get("/api/v1/requirements/" + req);
        alice.post("/api/v1/requirements/" + req + "/submit", null, RequirementFixtures.ifMatch(detail), true);
        process(WorkerContext.bean(JobWorker.class));
        assertThat(alice.get("/api/v1/requirements/" + req).body().path("state").asString()).isEqualTo("MANUAL_INTAKE");

        String doc = upload(req, "idea.txt", Samples.text("New product idea " + UUID.randomUUID()));
        process(WorkerContext.bean(JobWorker.class));

        assertThat(state(doc)).isEqualTo("READY_FOR_EXTRACTION");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.job WHERE job_type = 'DOCUMENT_EXTRACT' AND aggregate_id = ?::uuid",
                Integer.class, doc)).isZero();
    }

    /** The O-15 gate: with extraction switched off nothing is enqueued and the version stays READY_FOR_EXTRACTION. */
    @Test
    void extractionGateOffEnqueuesNothing() {
        String req = requirement("EXISTING_REPOSITORY");
        String doc = upload(req, "gated.txt", Samples.text("gated " + UUID.randomUUID()));
        String version = admin.queryForObject("SELECT id::text FROM app.document_version WHERE document_id = ?::uuid",
                String.class, doc);
        var consumer = new ExtractionConsumers().extractionEnqueueConsumer(WorkerContext.bean(JobQueue.class),
                WorkerContext.bean(WorkflowEngine.class), false);
        TransactionTemplate tx = new TransactionTemplate(WorkerContext.bean(PlatformTransactionManager.class));
        TenantContext.callAs(acme, () -> tx.execute(status -> {
            consumer.handle(new DomainEvent(UUID.randomUUID(), 1, acme, "DOCUMENT", UUID.fromString(doc), "DocumentScanned", 1,
                    Map.of("documentId", doc, "versionId", version, "requirementId", req), 0));
            return null;
        }));
        assertThat(admin.queryForObject("SELECT count(*) FROM app.job WHERE idempotency_key = ?", Integer.class,
                "extract:" + version)).isZero();
    }

    /** The extractor is untrusted: malformed or inconsistent output is never stored (OUTPUT_INVALID). */
    @Test
    void untrustedExtractorOutputIsValidated() throws Exception {
        ExtractionHandler handler = handler(Duration.ofSeconds(60));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(out.resolve("chunks.jsonl"), "{\"ordinal\":1,\"label\":null,\"page\":null,\"text\":\"ok\"}\n");
        Files.writeString(out.resolve("manifest.json"),
                "{\"outcome\":\"PROCESSED\",\"reason\":null,\"chunks\":1,\"chars\":2,\"sha256\":\"" + "0".repeat(64) + "\"}");
        assertThat(handler.readValidated(out).reason()).isEqualTo("OUTPUT_INVALID");

        Files.writeString(out.resolve("manifest.json"), "{\"outcome\":\"APPROVED\",\"reason\":null}");
        assertThat(handler.readValidated(out).reason()).isEqualTo("OUTPUT_INVALID");

        Files.writeString(out.resolve("chunks.jsonl"), "{\"ordinal\":2,\"label\":null,\"page\":null,\"text\":\"skip\"}\n");
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(out.resolve("chunks.jsonl"))));
        Files.writeString(out.resolve("manifest.json"),
                "{\"outcome\":\"PROCESSED\",\"reason\":null,\"chunks\":1,\"chars\":4,\"sha256\":\"" + sha + "\"}");
        assertThat(handler.readValidated(out).reason()).isEqualTo("OUTPUT_INVALID");

        Files.write(out.resolve("chunks.jsonl"), new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}', '\n'});
        assertThat(handler.readValidated(out).reason()).isEqualTo("OUTPUT_INVALID");
        assertThat(Files.readString(out.resolve("manifest.json"), StandardCharsets.UTF_8)).contains("PROCESSED");
    }

    /** Transient failures (here: the extractor never finishes in time) are retried three times, then final. */
    @Test
    void timeoutsAreRetriedThenEndInExtractionFailedFinal() {
        String req = requirement("EXISTING_REPOSITORY");
        String doc = upload(req, "slow.txt", Samples.text("slow " + UUID.randomUUID()));
        JobWorker scanOnly = WorkerContext.bean(JobWorker.class);
        OutboxDispatcher dispatcher = WorkerContext.bean(OutboxDispatcher.class);
        while (dispatcher.dispatchNext(acme) != OutboxDispatcher.Outcome.NONE) {
            // enqueue scan
        }
        for (JdbcJobStore.Claim claim : scanOnly.claim(acme, 20)) {
            if (claim.job().jobType().equals("DOCUMENT_SCAN")) {
                scanOnly.process(claim);
            }
        }
        while (dispatcher.dispatchNext(acme) != OutboxDispatcher.Outcome.NONE) {
            // enqueue extraction
        }
        String version = admin.queryForObject("SELECT id::text FROM app.document_version WHERE document_id = ?::uuid",
                String.class, doc);
        JobWorker slow = TestWorkers.create(List.of(handler(Duration.ofMillis(1))));
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                admin.update("UPDATE app.job SET available_at = clock_timestamp() WHERE idempotency_key = ? AND status = 'QUEUED'",
                        "extract:" + version);
                for (JdbcJobStore.Claim claim : slow.claim(acme, 20)) {
                    if (claim.job().payload().get("versionId").equals(version)) {
                        slow.process(claim);
                    }
                }
            }
        } finally {
            TestWorkers.shutdown(slow);
        }
        assertThat(admin.queryForMap("SELECT status, dead_reason, last_error_code FROM app.job WHERE idempotency_key = ?",
                "extract:" + version)).containsEntry("status", "DEAD").containsEntry("dead_reason", "RETRIES_EXHAUSTED")
                .containsEntry("last_error_code", "EXTRACTOR_TIMEOUT");
        while (dispatcher.dispatchNext(acme) != OutboxDispatcher.Outcome.NONE) {
            // deliver JobDead
        }
        assertThat(state(doc)).isEqualTo("EXTRACTION_FAILED_FINAL");
        assertThat(admin.queryForObject("SELECT reason_code FROM app.document_version WHERE document_id = ?::uuid",
                String.class, doc)).isEqualTo("EXTRACTION_RETRIES_EXHAUSTED");
        // Workspaces are cleaned after every attempt.
        assertThat(admin.queryForObject("SELECT count(*) FROM app.document_chunk c JOIN app.document_version v "
                + "ON v.id = c.version_id WHERE v.document_id = ?::uuid", Integer.class, doc)).isZero();
    }

    private ExtractionHandler handler(Duration timeout) {
        return new ExtractionHandler(WorkerContext.bean(DocumentProcessing.class), WorkerContext.bean(ObjectStore.class),
                new ExtractorLauncher(timeout, "256m"), WorkerContext.bean(JdbcClient.class),
                WorkerContext.bean(JsonMapper.class), WorkerContext.bean(PlatformTransactionManager.class),
                tmp.resolve("work"));
    }
}
