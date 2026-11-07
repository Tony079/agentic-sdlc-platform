package io.github.tony079.sdlc.document.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Document-state commands for the processing module (TDD 9.7: document transitions are owned by the documents module).
 * Worker only; every method runs in the caller's transaction and tenant context.
 */
public interface DocumentProcessing {

    /** Metadata needed to process a version. Never contains content. */
    record Input(UUID versionId, UUID documentId, UUID requirementId, String state, String type, String storageKey,
                 String sha256, long size) {
    }

    Optional<Input> input(UUID versionId);

    /** The current version of a document, if any. */
    Optional<UUID> currentVersion(UUID documentId);

    /**
     * Whether a current attachment of the requirement needs a decision (TDD T07/T10): its current version is
     * UNSUPPORTED_CONTENT, EXTRACTION_FAILED_FINAL or UPLOAD_FAILED.
     */
    boolean needsDecision(UUID requirementId);

    /**
     * The current version of every attached document of a requirement (deleted documents excluded), with the latest
     * PROCESSED extraction if any. Input to guard 9.4 and the analysis input set (TDD 12.3). Metadata only.
     */
    record AnalysisDocument(UUID documentId, UUID versionId, String fileName, String state, Integer extractionNo,
                            String outputSha256) {
    }

    java.util.List<AnalysisDocument> analysisDocuments(UUID requirementId);

    /** Extracted text chunks of one PROCESSED extraction (untrusted client content). */
    record Chunk(int ordinal, String label, Integer pageNo, String text) {
    }

    java.util.List<Chunk> chunks(UUID versionId, int extractionNo);

    /**
     * Records the processing outcome: READY_FOR_EXTRACTION to PROCESSED, UNSUPPORTED_CONTENT or
     * EXTRACTION_FAILED_FINAL. Locks the version first; returns false (and changes nothing) if it is no longer
     * READY_FOR_EXTRACTION. Publishes DocumentProcessed or DocumentFailed and writes the audit event.
     */
    boolean recordOutcome(UUID versionId, String outcome, String reasonCode);
}
