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

    /**
     * One validated source of a requirement: repository, pinned commit and its snapshot (snapshotStatus STORED, INDEXED
     * or FAILED). Environment sources (GD-23) carry their branch, and their file paths are shown to analysis with the
     * prefix "owner/name/" so that evidence names the repository; a legacy linked repository has the prefix "".
     */
    record PinnedRepository(UUID repositoryId, String commitSha, UUID snapshotId, String snapshotStatus,
                            String fullName, String branch, String prefix) {
    }

    /** Where a snapshot's files appear in a combined inventory. */
    record Source(UUID snapshotId, String prefix) {
    }

    /**
     * The sources of the requirement's latest round, ordered by prefix; empty while any of them is not validated or
     * has no snapshot yet. Requires an active transaction in the requirement's tenant.
     */
    List<PinnedRepository> sources(UUID requirementId);

    /** Whether the requirement's latest round has sources that are still being prepared (not all with a snapshot). */
    boolean sourcesPending(UUID requirementId);

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

    /** Combined inventory of several snapshots, every path prefixed with its source's prefix; bounds apply per source. */
    default Inventory inventory(List<Source> sources, int maxFiles, int maxSymbols) {
        if (sources.size() == 1 && sources.get(0).prefix().isEmpty()) {
            return inventory(sources.get(0).snapshotId(), maxFiles, maxSymbols);
        }
        List<FileInfo> files = new java.util.ArrayList<>();
        List<SymbolInfo> symbols = new java.util.ArrayList<>();
        List<DependencyInfo> dependencies = new java.util.ArrayList<>();
        for (Source source : sources) {
            Inventory one = inventory(source.snapshotId(), maxFiles, maxSymbols);
            String p = source.prefix();
            one.files().forEach(f -> files.add(new FileInfo(p + f.path(), f.language(), f.sizeBytes(), f.indexable())));
            one.symbols().forEach(x -> symbols.add(new SymbolInfo(p + x.path(), x.kind(), x.packageName(), x.typeName(),
                    x.memberName(), x.stereotype(), x.httpMethod(), x.httpPath(), x.startLine(), x.endLine())));
            one.dependencies().forEach(d -> dependencies.add(new DependencyInfo(p + d.path(), d.buildTool(), d.groupId(),
                    d.artifactId(), d.version())));
        }
        return new Inventory(files, symbols, dependencies);
    }

    /**
     * UTF-8 text of the given files from the stored snapshot (files over 1 MB are omitted). Must be called outside a
     * transaction (streams the encrypted archive); runs its own short read in the current tenant.
     */
    Map<String, String> readFiles(UUID snapshotId, Set<String> paths) throws IOException;

    /** As {@link #readFiles(UUID, Set)} across sources: each prefixed path is read from its own snapshot. */
    default Map<String, String> readFiles(List<Source> sources, Set<String> paths) throws IOException {
        if (sources.size() == 1 && sources.get(0).prefix().isEmpty()) {
            return readFiles(sources.get(0).snapshotId(), paths);
        }
        Map<String, String> out = new java.util.HashMap<>();
        for (Source source : sources) {
            Set<String> mine = new java.util.HashSet<>();
            for (String path : paths) {
                if (path.startsWith(source.prefix())) {
                    mine.add(path.substring(source.prefix().length()));
                }
            }
            readFiles(source.snapshotId(), mine).forEach((path, text) -> out.put(source.prefix() + path, text));
        }
        return out;
    }
}
