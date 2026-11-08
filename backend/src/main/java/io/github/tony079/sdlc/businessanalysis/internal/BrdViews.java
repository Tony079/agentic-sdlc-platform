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

    /** TDD 16.8 minimal comparison (O-22 provisional): statement level, by item_key, read-only. */
    record VersionDiff(VersionRef from, VersionRef to, List<ItemView> added, List<ItemView> removed,
                       List<ChangedItem> changed, int unchanged) {
    }

    record VersionRef(UUID id, int versionNo, String status, String contentHash) {
    }

    /** Same item_key (same section and statement); kind, support, risk, component or evidence differ. */
    record ChangedItem(String itemKey, ItemView before, ItemView after, List<String> fields) {
    }

    static VersionDiff diff(VersionView from, VersionView to) {
        java.util.Map<String, ItemView> before = new java.util.LinkedHashMap<>();
        from.items().forEach(i -> before.put(i.itemKey(), i));
        java.util.Map<String, ItemView> after = new java.util.LinkedHashMap<>();
        to.items().forEach(i -> after.put(i.itemKey(), i));
        List<ItemView> added = after.values().stream().filter(i -> !before.containsKey(i.itemKey())).toList();
        List<ItemView> removed = before.values().stream().filter(i -> !after.containsKey(i.itemKey())).toList();
        List<ChangedItem> changed = new java.util.ArrayList<>();
        int unchanged = 0;
        for (ItemView b : before.values()) {
            ItemView a = after.get(b.itemKey());
            if (a == null) {
                continue;
            }
            List<String> fields = new java.util.ArrayList<>();
            if (!java.util.Objects.equals(a.kind(), b.kind())) {
                fields.add("kind");
            }
            if (!java.util.Objects.equals(a.supportState(), b.supportState())) {
                fields.add("supportState");
            }
            if (!java.util.Objects.equals(a.risk(), b.risk())) {
                fields.add("risk");
            }
            if (!java.util.Objects.equals(a.component(), b.component())) {
                fields.add("component");
            }
            if (!evidenceKeys(a).equals(evidenceKeys(b))) {
                fields.add("evidence");
            }
            if (fields.isEmpty()) {
                unchanged++;
            } else {
                changed.add(new ChangedItem(b.itemKey(), b, a, List.copyOf(fields)));
            }
        }
        return new VersionDiff(ref(from), ref(to), added, removed, List.copyOf(changed), unchanged);
    }

    private static VersionRef ref(VersionView v) {
        return new VersionRef(v.id(), v.versionNo(), v.status(), v.contentHash());
    }

    private static List<String> evidenceKeys(ItemView item) {
        return item.evidence().stream().map(EvidenceView::toString).sorted().toList();
    }

    /** {@code versions}: every version of the requirement, newest first, for the comparison picker. */
    record ReviewDetail(String requirementKey, RequirementView.Content requirement, String state, VersionView version,
                        List<VersionRef> versions) {
    }

    record ClientBrd(UUID requirementId, String state, VersionView version) {
    }

    record NotificationView(UUID id, String kind, UUID requirementId, OffsetDateTime createdAt, OffsetDateTime readAt) {
    }
}
