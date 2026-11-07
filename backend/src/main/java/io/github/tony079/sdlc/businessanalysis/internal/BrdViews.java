package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.requirement.api.RequirementView;

/** API representations of BRD versions. Client views never carry source excerpts (AC-EXT-29). */
final class BrdViews {

    private BrdViews() {
    }

    record EvidenceView(String type, String path, Integer startLine, Integer endLine, String commitSha,
                        UUID documentVersionId, String fileName, Integer chunk, String chunkLabel, String quote,
                        Boolean supportPassed) {
    }

    record ItemView(String itemKey, int ordinal, String kind, String proposedKind, String section, String statement,
                    String risk, String component, String supportState, int rejectedCitations, boolean acknowledged,
                    List<EvidenceView> evidence) {
    }

    record VersionView(UUID id, UUID requirementId, int versionNo, String status, String contentHash, String inputHash,
                       String understanding, String excludedDocuments, String modelProvider, String modelId,
                       OffsetDateTime createdAt, List<ItemView> items) {
    }

    record ReviewQueueEntry(UUID requirementId, String requirementKey, String title, UUID brdVersionId, int versionNo,
                            int flagged, int unacknowledged, OffsetDateTime createdAt) {
    }

    record ReviewDetail(String requirementKey, RequirementView.Content requirement, String state, VersionView version) {
    }

    record ClientBrd(UUID requirementId, String state, VersionView version) {
    }

    record NotificationView(UUID id, String kind, UUID requirementId, OffsetDateTime createdAt, OffsetDateTime readAt) {
    }
}
