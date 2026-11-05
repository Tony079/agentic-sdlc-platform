package io.github.tony079.sdlc.document.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Attachment metadata shown to the uploader (PRD FR-UPL-02). Never contains file content or scanner details. */
public final class DocumentView {

    private DocumentView() {
    }

    /**
     * @param state        PRD section 6C state of the current version
     * @param reasonCode   why a version was rejected or not analysed, if so
     * @param downloadable true only after a clean scan
     */
    public record Item(UUID documentId, UUID versionId, int versionNo, String fileName, String type, long sizeBytes,
                       String state, String reasonCode, UUID uploadedBy, OffsetDateTime uploadedAt,
                       boolean downloadable) {
    }

    public record ItemList(List<Item> items, Limits limits) {
    }

    public record Limits(long maxFileBytes, int maxFiles, long maxTotalBytes) {
    }
}
