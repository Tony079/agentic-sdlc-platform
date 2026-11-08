package io.github.tony079.sdlc.sourcerepo.api;

import java.io.IOException;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Read-only, static access to the files of a stored repository snapshot (worker only). The archive is streamed from
 * the encrypted object store and checked against the pinned commit; nothing is extracted to disk and no repository
 * code is ever executed. Files over 1 MB are skipped.
 */
public interface SnapshotContent {

    /** The pinned commit of a stored or indexed snapshot of the current tenant, if any. */
    java.util.Optional<String> commitSha(UUID snapshotId);

    /** Hands each selected file's content to {@code visitor}; returns the number of files visited. */
    int forEachFile(UUID snapshotId, Predicate<String> paths, BiConsumer<String, byte[]> visitor) throws IOException;
}
