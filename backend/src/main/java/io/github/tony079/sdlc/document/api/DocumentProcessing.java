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
     * Records the processing outcome: READY_FOR_EXTRACTION to PROCESSED, UNSUPPORTED_CONTENT or
     * EXTRACTION_FAILED_FINAL. Locks the version first; returns false (and changes nothing) if it is no longer
     * READY_FOR_EXTRACTION. Publishes DocumentProcessed or DocumentFailed and writes the audit event.
     */
    boolean recordOutcome(UUID versionId, String outcome, String reasonCode);
}
