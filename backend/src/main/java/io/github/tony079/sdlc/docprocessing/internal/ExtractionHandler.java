package io.github.tony079.sdlc.docprocessing.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.tony079.sdlc.docprocessing.internal.extractor.ExtractorMain;
import io.github.tony079.sdlc.document.api.DocumentProcessing;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job DOCUMENT_EXTRACT (PRD 7A.3, TDD 15.5/15.6): the trusted coordinator. Copies the scanned file into a private
 * per-job, per-epoch workspace (checking its hash), runs the isolated extractor process, then validates every byte
 * the extractor wrote (manifest schema, chunk hash, ordinals, sizes, UTF-8) before anything reaches the database.
 * The result is committed under the job lease with the version's state change; the workspace is deleted in every case.
 *
 * <p>Deterministic outcomes (encrypted, image-only, corrupt, over limits, invalid output) are final and not retried.
 * Transient failures (timeout, extractor crash, storage read error) are retried up to the job type's three attempts;
 * a dead job ends in EXTRACTION_FAILED_FINAL (the dead-job consumer in {@link ExtractionConsumers}). A processing failure never changes the
 * requirement's workflow state (FR-DPR-10).
 */
@Component
@Profile("!migrate")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class ExtractionHandler implements JobHandler<ExtractionHandler.Result> {

    static final String JOB_TYPE = "DOCUMENT_EXTRACT";
    static final String EXTRACTOR = "builtin-extractor-1";
    private static final Logger log = LoggerFactory.getLogger(ExtractionHandler.class);
    private static final Set<String> OUTCOMES = Set.of("PROCESSED", "UNSUPPORTED_CONTENT", "EXTRACTION_FAILED_FINAL");

    record Chunk(int ordinal, String label, Integer page, String text, String sha256) {
    }

    /** @param outcome null when there is nothing to record (version already handled) */
    record Result(String outcome, String reason, List<Chunk> chunks, int chars, String sha256) {
        static final Result NOTHING = new Result(null, null, List.of(), 0, null);

        static Result finalFailure(String outcome, String reason) {
            return new Result(outcome, reason, List.of(), 0, null);
        }
    }

    private final DocumentProcessing documents;
    private final ObjectStore storage;
    private final ExtractorLauncher launcher;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Path workspace;

    ExtractionHandler(DocumentProcessing documents, ObjectStore storage, ExtractorLauncher launcher, JdbcClient jdbc,
                      JsonMapper json, PlatformTransactionManager transactionManager,
                      @Value("${sdlc.worker.workspace:${java.io.tmpdir}/sdlc-work}") Path workspace) {
        this.documents = documents;
        this.storage = storage;
        this.launcher = launcher;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.workspace = workspace;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public Result execute(ClaimedJob job) throws JobFailure {
        UUID versionId = versionId(job);
        DocumentProcessing.Input input = tx.execute(status -> documents.input(versionId).orElse(null));
        if (input == null) {
            throw JobFailure.fatal("DOCUMENT_NOT_FOUND", null);
        }
        if (!"READY_FOR_EXTRACTION".equals(input.state())) {
            return Result.NOTHING;
        }
        Path jobDir = workspace.resolve("extract-" + job.id());
        Path dir = jobDir.resolve(Long.toString(job.leaseEpoch()));
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("input");
            try (InputStream in = storage.open(input.storageKey());
                 DigestOutputStream out = new DigestOutputStream(Files.newOutputStream(file), sha256())) {
                long size = in.transferTo(out);
                String sha = HexFormat.of().formatHex(out.getMessageDigest().digest());
                if (size != input.size() || !sha.equals(input.sha256())) {
                    throw JobFailure.fatal("STORAGE_INTEGRITY_FAILED", null);
                }
            }
            Path outTmp = dir.resolve("out.tmp");
            ExtractorLauncher.Status status = launcher.run(file, input.type(), outTmp, dir);
            switch (status) {
                case TIMED_OUT -> throw JobFailure.retryable("EXTRACTOR_TIMEOUT", null);
                case CRASHED -> throw JobFailure.retryable("EXTRACTOR_CRASHED", null);
                case COMPLETED -> {
                }
            }
            return readValidated(outTmp);
        } catch (IOException e) {
            throw JobFailure.retryable("EXTRACTION_IO_FAILED", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw JobFailure.retryable("EXTRACTION_INTERRUPTED", e);
        } finally {
            deleteRecursively(jobDir);
        }
    }

    /** Validates the untrusted extractor output; anything malformed is a final OUTPUT_INVALID, never stored. */
    Result readValidated(Path out) throws IOException {
        Path manifestFile = out.resolve("manifest.json");
        Path chunksFile = out.resolve("chunks.jsonl");
        if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(chunksFile) || Files.size(manifestFile) > 4096
                || Files.size(chunksFile) > 4L * ExtractorMain.MAX_TOTAL_CHARS + 1_000_000) {
            return invalidOutput();
        }
        JsonNode manifest;
        try {
            manifest = json.readTree(Files.readString(manifestFile, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return invalidOutput();
        }
        String outcome = manifest.path("outcome").asString("");
        if (!OUTCOMES.contains(outcome)) {
            return invalidOutput();
        }
        String reason = manifest.path("reason").isNull() ? null : manifest.path("reason").asString(null);
        if (reason != null && !reason.matches("^[A-Z][A-Z0-9_]{1,63}$")) {
            return invalidOutput();
        }
        if (!"PROCESSED".equals(outcome)) {
            return Result.finalFailure(outcome, reason);
        }
        MessageDigest digest = sha256();
        List<Chunk> chunks = new ArrayList<>();
        int chars = 0;
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                new DigestInputStream(Files.newInputStream(chunksFile), digest),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode node = json.readTree(line);
                int ordinal = node.path("ordinal").asInt(-1);
                String text = node.path("text").asString("");
                String label = node.path("label").isNull() ? null : node.path("label").asString(null);
                Integer page = node.path("page").isNull() ? null : node.path("page").asInt(-1);
                if (ordinal != chunks.size() + 1 || text.isEmpty() || text.length() > 4000
                        || (label != null && label.length() > 200) || (page != null && page < 1)
                        || chunks.size() >= ExtractorMain.MAX_CHUNKS) {
                    return invalidOutput();
                }
                chars += text.length();
                if (chars > ExtractorMain.MAX_TOTAL_CHARS) {
                    return invalidOutput();
                }
                chunks.add(new Chunk(ordinal, label, page, text,
                        HexFormat.of().formatHex(sha256().digest(text.getBytes(StandardCharsets.UTF_8)))));
            }
        } catch (RuntimeException | java.nio.charset.CharacterCodingException e) {
            return invalidOutput();
        }
        String sha = HexFormat.of().formatHex(digest.digest());
        if (!sha.equals(manifest.path("sha256").asString("")) || manifest.path("chunks").asInt(-1) != chunks.size()
                || manifest.path("chars").asInt(-1) != chars || chunks.isEmpty()) {
            return invalidOutput();
        }
        return new Result("PROCESSED", null, List.copyOf(chunks), chars, sha);
    }

    @Override
    public void commit(ClaimedJob job, Result result) {
        if (result.outcome() == null) {
            return;
        }
        UUID versionId = versionId(job);
        if (!documents.recordOutcome(versionId, result.outcome(), result.reason())) {
            return;
        }
        int extractionNo = jdbc.sql("""
                SELECT coalesce(max(extraction_no), 0) + 1 FROM app.document_extraction WHERE version_id = :version
                """).param("version", versionId).query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO app.document_extraction (tenant_id, version_id, extraction_no, outcome, reason_code,
                                                     chunk_count, char_count, output_sha256, extractor)
                VALUES (:tenant, :version, :no, :outcome, :reason, :chunks, :chars, :sha, :extractor)
                """)
                .param("tenant", job.tenantId()).param("version", versionId).param("no", extractionNo)
                .param("outcome", result.outcome()).param("reason", result.reason())
                .param("chunks", result.chunks().size()).param("chars", result.chars())
                .param("sha", result.sha256()).param("extractor", EXTRACTOR)
                .update();
        for (Chunk chunk : result.chunks()) {
            jdbc.sql("""
                    INSERT INTO app.document_chunk (tenant_id, version_id, extraction_no, ordinal, label, page_no, text,
                                                    chunk_sha256)
                    VALUES (:tenant, :version, :no, :ordinal, :label, :page, :text, :sha)
                    """)
                    .param("tenant", job.tenantId()).param("version", versionId).param("no", extractionNo)
                    .param("ordinal", chunk.ordinal()).param("label", chunk.label()).param("page", chunk.page())
                    .param("text", chunk.text()).param("sha", chunk.sha256())
                    .update();
        }
    }

    private static Result invalidOutput() {
        log.warn("Extractor output failed validation; recorded as EXTRACTION_FAILED_FINAL");
        return Result.finalFailure("EXTRACTION_FAILED_FINAL", "OUTPUT_INVALID");
    }

    private static UUID versionId(ClaimedJob job) {
        return UUID.fromString(String.valueOf(job.payload().get("versionId")));
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
