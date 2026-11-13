package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.outbox.internal.OutboxDispatcher;
import io.github.tony079.sdlc.queue.internal.JdbcJobStore;
import io.github.tony079.sdlc.queue.internal.JobWorker;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.FakeClamd;
import io.github.tony079.sdlc.support.RequirementFixtures;
import io.github.tony079.sdlc.support.Samples;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.TestStorage;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Milestone 4 end to end (PRD 7A, TDD 15.2/15.3): upload through the API into private encrypted storage, the worker
 * validates the real content and scans it (protocol-level clamd test double), and only clean files become
 * downloadable. Covers limits, duplicates, hostile files, malware, scanner outage (fail closed), removal, owner-only
 * and cross-tenant access, and the database boundary that stops the API from forging scan results.
 */
class DocumentApiIT extends ApiIntegrationTest {

    private final JdbcTemplate admin = TestDatabase.get().admin();
    private ApiClient alice;
    private UUID acme;
    private String requirement;

    @BeforeEach
    void setUp() {
        WorkerContext.get();
        alice = loggedIn("acme", "alice@acme.test");
        acme = TestDatabase.get().tenantId("acme");
        String project = RequirementFixtures.project(alice);
        Response created = alice.post("/api/v1/requirements", RequirementFixtures.complete(project));
        requirement = created.body().path("id").asString();
    }

    @AfterEach
    void scannerUp() {
        FakeClamd.down = false;
    }

    private String documents() {
        return "/api/v1/requirements/" + requirement + "/documents";
    }

    private Response upload(String name, String type, byte[] content) {
        return alice.upload(documents(), name, type, content);
    }

    /** Delivers the upload events (enqueues scan jobs) and runs this tenant's scan jobs once. */
    private void process() {
        OutboxDispatcher dispatcher = WorkerContext.bean(OutboxDispatcher.class);
        while (dispatcher.dispatchNext(acme) != OutboxDispatcher.Outcome.NONE) {
            // drain
        }
        JobWorker worker = WorkerContext.bean(JobWorker.class);
        for (JdbcJobStore.Claim claim : worker.claim(acme, 20)) {
            worker.process(claim);
        }
    }

    private Map<String, Object> version(String documentId) {
        return admin.queryForMap("""
                SELECT state, scan_result, scanner, reason_code, storage_key, file_name, size_bytes
                FROM app.document_version WHERE document_id = ?::uuid
                """, documentId);
    }

    @Test
    void cleanFileIsStoredEncryptedScannedAndThenDownloadable() throws Exception {
        byte[] content = Samples.text("Process notes: repayments must carry a channel code.");
        Response uploaded = upload("notes.txt", "text/plain", content);
        assertThat(uploaded.status()).isEqualTo(201);
        String id = uploaded.body().path("documentId").asString();
        assertThat(uploaded.body().path("state").asString()).isEqualTo("QUARANTINED");
        assertThat(uploaded.body().path("downloadable").asBoolean()).isFalse();
        assertThat(alice.download(documents() + "/" + id + "/content").status()).isEqualTo(409);

        Map<String, Object> stored = version(id);
        assertThat((String) stored.get("storage_key")).doesNotContain("notes");
        String onDisk = Files.readString(TestStorage.ROOT.resolve((String) stored.get("storage_key")),
                StandardCharsets.ISO_8859_1);
        assertThat(onDisk).doesNotContain("repayments");

        process();

        assertThat(version(id)).containsEntry("state", "READY_FOR_EXTRACTION").containsEntry("scan_result", "CLEAN")
                .containsEntry("scanner", "clamav");
        ApiClient.Download download = alice.download(documents() + "/" + id + "/content");
        assertThat(download.status()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(content);
        assertThat(download.header("Content-Type")).isEqualTo("application/octet-stream");
        assertThat(download.header("Content-Disposition")).startsWith("attachment;");
        assertThat(download.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(admin.queryForList("SELECT action FROM app.audit_event WHERE resource_id = ?::uuid ORDER BY occurred_at",
                String.class, id)).containsExactly("DOCUMENT_UPLOADED", "DOCUMENT_SCANNED_CLEAN", "DOCUMENT_DOWNLOADED");
        Response list = alice.get(documents());
        assertThat(list.body().path("items").get(0).path("downloadable").asBoolean()).isTrue();
        assertThat(list.body().path("limits").path("maxFiles").asInt()).isEqualTo(10);
    }

    @Test
    void validDocumentsOfEveryTypeAreAccepted() {
        assertThat(upload("spec.pdf", "application/pdf", Samples.pdf("spec")).status()).isEqualTo(201);
        assertThat(upload("brd.docx", null, Samples.docx("Existing BRD")).status()).isEqualTo(201);
        assertThat(upload("rules.md", "text/markdown", Samples.text("# Rules")).status()).isEqualTo(201);
        process();
        assertThat(admin.queryForList("SELECT state FROM app.document_version WHERE requirement_id = ?::uuid",
                String.class, requirement)).containsOnly("READY_FOR_EXTRACTION").hasSize(3);
    }

    @Test
    void unsupportedTypesAndMismatchedDeclarationsAreRejectedAtUpload() {
        assertThat(upload("tool.exe", "application/octet-stream", Samples.executable()).fieldErrors())
                .containsExactly("file:UNSUPPORTED_FILE_TYPE");
        assertThat(upload("bundle.zip", "application/zip", Samples.zip(Map.of("a", "b"))).fieldErrors())
                .containsExactly("file:UNSUPPORTED_FILE_TYPE");
        assertThat(upload("notes.pdf", "text/html", Samples.pdf("x")).fieldErrors())
                .containsExactly("file:CONTENT_TYPE_MISMATCH");
        assertThat(upload("empty.txt", "text/plain", new byte[0]).fieldErrors()).containsExactly("file:EMPTY_FILE");
        assertThat(upload("../../etc/passwd.txt", "text/plain", Samples.text("x")).status()).isEqualTo(201);
        assertThat(alice.get(documents()).body().path("items").get(0).path("fileName").asString()).isEqualTo("passwd.txt");
    }

    /** AC-DOC-03: content decides, not the extension. Renamed executables and archives end REJECTED_INVALID. */
    @Test
    void hostileContentIsRejectedByTheWorker() {
        String exe = upload("report.txt", "text/plain", Samples.executable()).body().path("documentId").asString();
        String zipAsPdf = upload("scan.pdf", "application/pdf", Samples.zip(Map.of("x", "y"))).body().path("documentId").asString();
        String bomb = upload("big.docx", null, Samples.docxBomb(20)).body().path("documentId").asString();
        String traversal = upload("evil.docx", null, Samples.zip(Map.of("[Content_Types].xml", "<Types/>",
                "word/document.xml", "<d/>", "../../x.sh", "boom"))).body().path("documentId").asString();
        process();
        assertThat(version(exe)).containsEntry("state", "REJECTED_INVALID").containsEntry("reason_code", "BINARY_CONTENT")
                .containsEntry("scan_result", null);
        assertThat(version(zipAsPdf)).containsEntry("reason_code", "CONTENT_TYPE_MISMATCH");
        assertThat(version(bomb)).containsEntry("reason_code", "ARCHIVE_LIMITS_EXCEEDED");
        assertThat(version(traversal)).containsEntry("reason_code", "UNSAFE_ARCHIVE_ENTRY");
        assertThat(alice.download(documents() + "/" + exe + "/content").status()).isEqualTo(409);
    }

    @Test
    void malwareIsRejectedNeverExposedAndRaisesAHighSecurityEvent() {
        String id = upload("invoice.txt", "text/plain", Samples.text("pay now " + FakeClamd.MARKER))
                .body().path("documentId").asString();
        process();
        assertThat(version(id)).containsEntry("state", "REJECTED_MALWARE").containsEntry("scan_result", "INFECTED")
                .containsEntry("reason_code", "MALWARE_DETECTED");
        assertThat(alice.download(documents() + "/" + id + "/content").status()).isEqualTo(409);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.platform_security_event WHERE type = 'MALWARE_DETECTED' AND severity = 'HIGH'
                  AND target_ref = ?
                """, Integer.class, "DOCUMENT:" + id)).isEqualTo(1);
        // The signature name stays in operator logs; nothing about it reaches the client.
        assertThat(alice.get(documents()).body().toString()).doesNotContain("Test.Marker");
    }

    /** Fail closed (AC-DOC-04, AC-EXT-22): with the scanner down the file stays QUARANTINED; no budget is used. */
    @Test
    void scannerOutageKeepsTheFileQuarantined() {
        FakeClamd.down = true;
        String id = upload("policy.txt", "text/plain", Samples.text("Policy " + UUID.randomUUID()))
                .body().path("documentId").asString();
        process();
        assertThat(version(id)).containsEntry("state", "QUARANTINED").containsEntry("scan_result", null);
        Map<String, Object> job = admin.queryForMap("""
                SELECT status, deferral_count, retryable_failure_count, last_error_code FROM app.job
                WHERE idempotency_key = 'scan:' || (SELECT id FROM app.document_version WHERE document_id = ?::uuid)
                """, id);
        assertThat(job).containsEntry("status", "QUEUED").containsEntry("deferral_count", 1)
                .containsEntry("retryable_failure_count", 0).containsEntry("last_error_code", "SCANNER_UNAVAILABLE");
        assertThat(alice.download(documents() + "/" + id + "/content").status()).isEqualTo(409);

        FakeClamd.down = false;
        admin.update("UPDATE app.job SET available_at = clock_timestamp() WHERE idempotency_key = 'scan:' "
                + "|| (SELECT id FROM app.document_version WHERE document_id = ?::uuid)", id);
        process();
        assertThat(version(id)).containsEntry("state", "READY_FOR_EXTRACTION");
    }

    @Test
    void duplicatesAndLimitsAreEnforcedOnTheServer() {
        byte[] content = Samples.text("same content " + UUID.randomUUID());
        assertThat(upload("a.txt", "text/plain", content).status()).isEqualTo(201);
        Response duplicate = upload("b.txt", "text/plain", content);
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.errorCode()).isEqualTo("DUPLICATE_DOCUMENT");
        for (int i = 0; i < 9; i++) {
            assertThat(upload("f" + i + ".txt", "text/plain", Samples.text("file " + i + UUID.randomUUID())).status())
                    .isEqualTo(201);
        }
        assertThat(upload("eleventh.txt", "text/plain", Samples.text("x" + UUID.randomUUID())).fieldErrors())
                .containsExactly("file:TOO_MANY_FILES");
        Response tooLarge = upload("huge.txt", "text/plain", new byte[20 * 1024 * 1024 + 1]);
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(tooLarge.errorCode()).isEqualTo("FILE_TOO_LARGE");
        // Only the 10 accepted files exist in storage metadata; refused uploads left no rows.
        assertThat(admin.queryForObject("SELECT count(*) FROM app.document WHERE requirement_id = ?::uuid",
                Integer.class, requirement)).isEqualTo(10);
    }

    @Test
    void removalOnlyBeforeSubmissionAndUploadsRefusedOnceTerminal() {
        String id = upload("draft.txt", "text/plain", Samples.text("draft " + UUID.randomUUID()))
                .body().path("documentId").asString();
        assertThat(alice.delete(documents() + "/" + id).status()).isEqualTo(204);
        assertThat(alice.get(documents()).body().path("items").size()).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.audit_event WHERE resource_id = ?::uuid AND action = 'DOCUMENT_REMOVED'",
                Integer.class, id)).isEqualTo(1);

        String kept = upload("kept.txt", "text/plain", Samples.text("kept " + UUID.randomUUID()))
                .body().path("documentId").asString();
        Response detail = alice.get("/api/v1/requirements/" + requirement);
        Response submitted = alice.post("/api/v1/requirements/" + requirement + "/submit", null,
                RequirementFixtures.ifMatch(detail), true);
        assertThat(submitted.status()).isEqualTo(200);
        Response locked = alice.delete(documents() + "/" + kept);
        assertThat(locked.status()).isEqualTo(409);
        assertThat(locked.errorCode()).isEqualTo("DOCUMENTS_LOCKED");
        // Still allowed after submission (PRD 0.4: also in manual intake); refused once cancelled.
        assertThat(upload("later.txt", "text/plain", Samples.text("later " + UUID.randomUUID())).status()).isEqualTo(201);
        Response current = alice.get("/api/v1/requirements/" + requirement);
        alice.post("/api/v1/requirements/" + requirement + "/cancel", Map.of("reason", "Not needed any more, thanks."),
                RequirementFixtures.ifMatch(current), true);
        Response refused = upload("after.txt", "text/plain", Samples.text("after " + UUID.randomUUID()));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("DOCUMENTS_LOCKED");
    }

    /**
     * Project and tenant isolation (AC-DOC-05, AC-DOC-06, FR-UPL-14, Garuda GD-05): another tenant and an organisation
     * member outside the project get 404 everywhere (recorded); a project Approver may list and download but only the
     * owner adds or removes attachments (403).
     */
    @Test
    void otherUsersAndTenantsCannotListUploadDownloadOrRemove() {
        String id = upload("private.txt", "text/plain", Samples.text("private " + UUID.randomUUID()))
                .body().path("documentId").asString();
        process();
        ApiClient outsider = loggedIn("acme", io.github.tony079.sdlc.support.RequirementFixtures.newMember("acme", "doc"));
        for (ApiClient intruder : new ApiClient[] {loggedIn("globex", "gina@globex.test"), outsider}) {
            assertThat(intruder.get(documents()).status()).isEqualTo(404);
            assertThat(intruder.download(documents() + "/" + id + "/content").status()).isEqualTo(404);
            assertThat(intruder.upload(documents(), "x.txt", "text/plain", Samples.text("x")).status()).isEqualTo(404);
            assertThat(intruder.delete(documents() + "/" + id).status()).isEqualTo(404);
        }
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.platform_security_event WHERE type = 'RESOURCE_NOT_FOUND' AND target_ref = ?
                """, Integer.class, "REQUIREMENT:" + requirement)).isGreaterThanOrEqualTo(8);

        ApiClient approver = loggedIn("acme", "aaron@acme.test");
        assertThat(approver.get(documents()).status()).isEqualTo(200);
        assertThat(approver.download(documents() + "/" + id + "/content").status()).isEqualTo(200);
        assertThat(approver.upload(documents(), "x.txt", "text/plain", Samples.text("x")).errorCode())
                .isEqualTo("OWNER_REQUIRED");
        assertThat(approver.delete(documents() + "/" + id).errorCode()).isEqualTo("OWNER_REQUIRED");
        assertThat(alice.download(documents() + "/" + id + "/content").status()).isEqualTo(200);
    }

    /** The API login can neither forge a scan result nor change a version; the database refuses unscanned states. */
    @Test
    void scanResultsCannotBeForgedOrSkipped() throws Exception {
        String id = upload("guarded.txt", "text/plain", Samples.text("guarded " + UUID.randomUUID()))
                .body().path("documentId").asString();
        try (Connection app = TestDatabase.get().appConnection(); Statement statement = app.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id', '" + acme + "', false)");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE app.document_version SET state = 'READY_FOR_EXTRACTION' WHERE document_id = '" + id + "'"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO app.document_version (tenant_id, document_id, "
                    + "requirement_id, version_no, file_name, claimed_type, size_bytes, sha256, storage_key, uploaded_by, "
                    + "state, scan_result, scanned_at) SELECT tenant_id, document_id, requirement_id, 2, 'x.txt', 'TXT', 1, "
                    + "repeat('b', 64), tenant_id || '/' || gen_random_uuid(), uploaded_by, 'READY_FOR_EXTRACTION', 'CLEAN', "
                    + "now() FROM app.document_version WHERE document_id = '" + id + "'"))
                    .hasMessageContaining("permission denied");
        }
        assertThatThrownBy(() -> admin.update(
                "UPDATE app.document_version SET state = 'READY_FOR_EXTRACTION' WHERE document_id = ?::uuid", id))
                .hasMessageContaining("document_version_clean_scan_required");
        process();
        assertThatThrownBy(() -> admin.update(
                "UPDATE app.document_version SET scan_result = 'INFECTED' WHERE document_id = ?::uuid", id))
                .hasMessageContaining("immutable");
    }
}
