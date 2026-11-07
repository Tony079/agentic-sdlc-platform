package io.github.tony079.sdlc.sourcerepo.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The pinned repository of a requirement as analysis input (TDD 12.3, 14.6). Worker only. Repository content is
 * untrusted client data: it is read from the stored snapshot of the pinned commit, never from GitHub, never executed.
 */
public interface RepositoryInputs {

    /** The validated repository, pinned commit and its snapshot (snapshotStatus STORED, INDEXED or FAILED). */
    record PinnedRepository(UUID repositoryId, String commitSha, UUID snapshotId, String snapshotStatus) {
    }

    /** Requires an active transaction in the requirement's tenant. */
    Optional<PinnedRepository> pinned(UUID requirementId);

    record FileInfo(String path, String language, long sizeBytes, boolean indexable) {
    }

    record SymbolInfo(String path, String kind, String packageName, String typeName, String memberName,
                      String stereotype, String httpMethod, String httpPath, int startLine, int endLine) {
    }

    record DependencyInfo(String path, String buildTool, String groupId, String artifactId, String version) {
    }

    record Inventory(List<FileInfo> files, List<SymbolInfo> symbols, List<DependencyInfo> dependencies) {
    }

    /** Inventory of an indexed snapshot, bounded. Requires an active transaction in the tenant. */
    Inventory inventory(UUID snapshotId, int maxFiles, int maxSymbols);

    /**
     * UTF-8 text of the given files from the stored snapshot (files over 1 MB are omitted). Must be called outside a
     * transaction (streams the encrypted archive); runs its own short read in the current tenant.
     */
    Map<String, String> readFiles(UUID snapshotId, Set<String> paths) throws IOException;
}
