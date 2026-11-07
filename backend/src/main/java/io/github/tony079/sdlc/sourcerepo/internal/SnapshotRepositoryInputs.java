package io.github.tony079.sdlc.sourcerepo.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs;
import io.github.tony079.sdlc.sourcerepo.internal.SnapshotArchive.SnapshotRejected;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.SnapshotRow;
import io.github.tony079.sdlc.storage.api.ObjectStore;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("!migrate & !provision")
class SnapshotRepositoryInputs implements RepositoryInputs {

    private final JdbcClient jdbc;
    private final WorkerRepository repositories;
    private final ObjectStore storage;
    private final TransactionTemplate readOnly;

    SnapshotRepositoryInputs(JdbcClient jdbc, WorkerRepository repositories, ObjectStore storage,
                             PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.repositories = repositories;
        this.storage = storage;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public Optional<PinnedRepository> pinned(UUID requirementId) {
        return jdbc.sql("""
                SELECT rr.repository_id, rr.pinned_sha, s.id, s.status
                FROM app.requirement_repository rr
                JOIN app.repository_snapshot s ON s.requirement_repository_id = rr.id
                WHERE rr.requirement_id = :requirement AND rr.status = 'VALIDATED'
                """).param("requirement", requirementId)
                .query((rs, n) -> new PinnedRepository(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getString(4)))
                .optional();
    }

    @Override
    public Inventory inventory(UUID snapshotId, int maxFiles, int maxSymbols) {
        List<FileInfo> files = jdbc.sql("""
                SELECT path, language, size_bytes, indexable FROM app.repository_file WHERE snapshot_id = :s
                ORDER BY path LIMIT :limit
                """).param("s", snapshotId).param("limit", maxFiles)
                .query((rs, n) -> new FileInfo(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4))).list();
        List<SymbolInfo> symbols = jdbc.sql("""
                SELECT file_path, kind, package_name, type_name, member_name, stereotype, http_method, http_path,
                       start_line, end_line
                FROM app.code_symbol WHERE snapshot_id = :s ORDER BY file_path, start_line, kind LIMIT :limit
                """).param("s", snapshotId).param("limit", maxSymbols)
                .query((rs, n) -> new SymbolInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9),
                        rs.getInt(10))).list();
        List<DependencyInfo> dependencies = jdbc.sql("""
                SELECT file_path, build_tool, group_id, artifact_id, version FROM app.build_dependency
                WHERE snapshot_id = :s ORDER BY file_path, artifact_id LIMIT 500
                """).param("s", snapshotId)
                .query((rs, n) -> new DependencyInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5))).list();
        return new Inventory(files, symbols, dependencies);
    }

    @Override
    public Map<String, String> readFiles(UUID snapshotId, Set<String> paths) throws IOException {
        Map<String, String> out = new HashMap<>();
        if (paths.isEmpty()) {
            return out;
        }
        SnapshotRow snapshot = readOnly.execute(status -> repositories.snapshot(snapshotId, false).orElse(null));
        if (snapshot == null || snapshot.storageKey() == null) {
            return out;
        }
        try (InputStream in = storage.open(snapshot.storageKey())) {
            SnapshotArchive.read(in, snapshot.archiveBytes(), snapshot.commitSha(),
                    (path, language, content) -> out.put(path, new String(content, StandardCharsets.UTF_8)),
                    SnapshotArchive.Limits.DEFAULT, paths::contains);
        } catch (SnapshotRejected e) {
            throw new IOException("Stored snapshot no longer passes its checks: " + e.code());
        }
        return out;
    }
}
