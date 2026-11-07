package io.github.tony079.sdlc.document.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.scanner.api.MalwareScanner;
import io.github.tony079.sdlc.scanner.api.MalwareScanner.ScannerUnavailableException;
import io.github.tony079.sdlc.scanner.api.MalwareScanner.Verdict;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;
import io.github.tony079.sdlc.storage.api.ObjectStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Job DOCUMENT_SCAN (TDD 15.2/15.3 "validate, then scan"), worker only. The stored object is decrypted into a
 * private per-job, per-epoch workspace, its hash and size are checked against the recorded metadata, its real
 * content is validated against the claimed type, and only then is it streamed to the malware scanner. The
 * workspace is deleted afterwards in every case.
 *
 * <p>Outcomes: clean → READY_FOR_EXTRACTION; invalid → REJECTED_INVALID with a reason code; threat →
 * REJECTED_MALWARE, a HIGH security event and an alert. Scanner unreachable or failing → the job is deferred
 * without using its retry budget and the version stays QUARANTINED (fail closed, PRD FR-STO-12, AC-DOC-04).
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class DocumentScanHandler implements JobHandler<DocumentScanHandler.Outcome> {

    static final String JOB_TYPE = "DOCUMENT_SCAN";
    private static final Logger log = LoggerFactory.getLogger(DocumentScanHandler.class);

    /** @param state null when there is nothing to record (already handled) */
    record Outcome(String state, String scanResult, String reasonCode, String signature) {
        static final Outcome NOTHING = new Outcome(null, null, null, null);
    }

    private record Meta(UUID documentId, UUID requirementId, String state, DocumentType type, String storageKey,
                        String sha256, long size) {
    }

    private final JdbcClient jdbc;
    private final ObjectStore storage;
    private final MalwareScanner scanner;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final SecurityEventRecorder securityEvents;
    private final TransactionTemplate tx;
    private final Path workspace;
    private final Duration scannerRetryDelay;

    DocumentScanHandler(JdbcClient jdbc, ObjectStore storage, MalwareScanner scanner, OutboxPublisher outbox,
                        AuditWriter audit, SecurityEventRecorder securityEvents, PlatformTransactionManager transactionManager,
                        @Value("${sdlc.worker.workspace:${java.io.tmpdir}/sdlc-work}") Path workspace,
                        @Value("${sdlc.scanner.retry-delay:30s}") Duration scannerRetryDelay) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.scanner = scanner;
        this.outbox = outbox;
        this.audit = audit;
        this.securityEvents = securityEvents;
        this.tx = new TransactionTemplate(transactionManager);
        this.workspace = workspace;
        this.scannerRetryDelay = scannerRetryDelay;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID versionId = versionId(job);
        Meta meta = tx.execute(status -> jdbc.sql("""
                SELECT document_id, requirement_id, state, claimed_type, storage_key, sha256, size_bytes
                FROM app.document_version WHERE id = :id
                """).param("id", versionId)
                .query((rs, n) -> new Meta(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        DocumentType.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getLong(7)))
                .optional().orElse(null));
        if (meta == null) {
            throw JobFailure.fatal("DOCUMENT_NOT_FOUND", null);
        }
        if (!"QUARANTINED".equals(meta.state())) {
            return Outcome.NOTHING;
        }
        Path dir = workspace.resolve(job.id().toString()).resolve(Long.toString(job.leaseEpoch()));
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("content");
            String sha;
            long size;
            try (InputStream in = storage.open(meta.storageKey());
                 DigestOutputStream out = new DigestOutputStream(Files.newOutputStream(file), sha256())) {
                size = in.transferTo(out);
                sha = HexFormat.of().formatHex(out.getMessageDigest().digest());
            }
            if (size != meta.size() || !sha.equals(meta.sha256())) {
                throw JobFailure.fatal("STORAGE_INTEGRITY_FAILED", null);
            }
            var invalid = DocumentContentValidator.validate(file, meta.type());
            if (invalid.isPresent()) {
                return new Outcome("REJECTED_INVALID", null, invalid.get(), null);
            }
            Verdict verdict;
            try (InputStream in = Files.newInputStream(file)) {
                verdict = scanner.scan(in);
            } catch (ScannerUnavailableException e) {
                log.warn("Scanner unavailable for document version {}: kept QUARANTINED", versionId);
                throw JobFailure.dependencyUnavailable("SCANNER_UNAVAILABLE", scannerRetryDelay);
            }
            return verdict.clean() ? new Outcome("READY_FOR_EXTRACTION", "CLEAN", null, null)
                    : new Outcome("REJECTED_MALWARE", "INFECTED", "MALWARE_DETECTED", verdict.signature());
        } catch (IOException e) {
            throw JobFailure.retryable("STORAGE_READ_FAILED", e);
        } finally {
            deleteRecursively(workspace.resolve(job.id().toString()));
        }
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        if (outcome.state() == null) {
            return;
        }
        UUID versionId = versionId(job);
        Meta meta = jdbc.sql("""
                SELECT document_id, requirement_id, state, claimed_type, storage_key, sha256, size_bytes
                FROM app.document_version WHERE id = :id FOR UPDATE
                """).param("id", versionId)
                .query((rs, n) -> new Meta(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        DocumentType.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getLong(7)))
                .single();
        if (!"QUARANTINED".equals(meta.state())) {
            return;
        }
        jdbc.sql("""
                UPDATE app.document_version
                SET state = :state, scan_result = :result, scanner = :scanner,
                    scanned_at = CASE WHEN CAST(:result AS text) IS NULL THEN NULL ELSE clock_timestamp() END,
                    reason_code = :reason, updated_at = clock_timestamp()
                WHERE id = :id
                """)
                .param("state", outcome.state()).param("result", outcome.scanResult())
                .param("scanner", outcome.scanResult() == null ? null : scanner.name())
                .param("reason", outcome.reasonCode()).param("id", versionId)
                .update();
        boolean clean = "READY_FOR_EXTRACTION".equals(outcome.state());
        outbox.publish("DOCUMENT", meta.documentId(), clean ? EventTypes.DOCUMENT_SCANNED : EventTypes.DOCUMENT_FAILED,
                Map.of("documentId", meta.documentId().toString(), "versionId", versionId.toString(),
                        "requirementId", meta.requirementId().toString(), "state", outcome.state()));
        audit.record(clean ? AuditAction.DOCUMENT_SCANNED_CLEAN : AuditAction.DOCUMENT_REJECTED, "DOCUMENT",
                meta.documentId(), clean ? AuditResult.SUCCESS : AuditResult.DENIED);
        if ("REJECTED_MALWARE".equals(outcome.state())) {
            // PRD 6C.1 rule 3 / AC-EXT-33: the file is never exposed; platform operators are alerted. The alert
            // channel for Platform Admins is open (C-17): security event plus an operator log alert.
            securityEvents.record(SecurityEventType.MALWARE_DETECTED, Severity.HIGH, "SYSTEM",
                    "DOCUMENT:" + meta.documentId());
            log.error("ALERT malware detected: document={} version={} tenant={} signature={}", meta.documentId(),
                    versionId, job.tenantId(), outcome.signature());
        }
    }

    private static UUID versionId(ClaimedJob job) {
        try {
            return UUID.fromString(String.valueOf(job.payload().get("versionId")));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("DOCUMENT_SCAN job without a version id");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete workspace file {}", p);
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean workspace {}", path);
        }
    }
}
