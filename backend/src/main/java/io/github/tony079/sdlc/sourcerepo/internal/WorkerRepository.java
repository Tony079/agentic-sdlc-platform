package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.sourcerepo.internal.BuildFileParser.Dependency;
import io.github.tony079.sdlc.sourcerepo.internal.JavaSymbolExtractor.Symbol;
import io.github.tony079.sdlc.sourcerepo.internal.SnapshotArchive.FileEntry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Worker-side repository data access, in the job's tenant transaction (forced RLS). */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class WorkerRepository {

    record RepositoryRow(UUID id, String owner, String name, String status, Long githubRepoId, String defaultBranch) {
    }

    record ValidationRow(UUID requirementRepositoryId, UUID requirementId, String status, String pinnedSha,
                         RepositoryRow repository) {
    }

    record SnapshotRow(UUID id, String commitSha, String status, String storageKey, long archiveBytes) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    WorkerRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    private static final String REPO_COLUMNS = "r.id, r.github_owner, r.github_name, r.status, r.github_repo_id, r.default_branch";

    private static RepositoryRow repo(java.sql.ResultSet rs, int offset) throws java.sql.SQLException {
        return new RepositoryRow(rs.getObject(offset, UUID.class), rs.getString(offset + 1), rs.getString(offset + 2),
                rs.getString(offset + 3), (Long) rs.getObject(offset + 4), rs.getString(offset + 5));
    }

    Optional<RepositoryRow> repository(UUID id, boolean lock) {
        return jdbc.sql("SELECT " + REPO_COLUMNS + " FROM app.repository r WHERE r.id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query((rs, n) -> repo(rs, 1)).optional();
    }

    private static final String VALIDATION_SELECT = "SELECT rr.id, rr.requirement_id, rr.status, rr.pinned_sha, "
            + REPO_COLUMNS + " FROM app.requirement_repository rr JOIN app.repository r ON r.id = rr.repository_id ";

    private static ValidationRow validation(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ValidationRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), repo(rs, 5));
    }

    Optional<ValidationRow> validationForRequirement(UUID requirementId) {
        return jdbc.sql(VALIDATION_SELECT + "WHERE rr.requirement_id = :id").param("id", requirementId)
                .query(WorkerRepository::validation).optional();
    }

    Optional<ValidationRow> validationById(UUID requirementRepositoryId) {
        return jdbc.sql(VALIDATION_SELECT + "WHERE rr.id = :id").param("id", requirementRepositoryId)
                .query(WorkerRepository::validation).optional();
    }

    List<UUID> pendingRequirements(UUID repositoryId) {
        return jdbc.sql("""
                SELECT requirement_id FROM app.requirement_repository
                WHERE repository_id = :repo AND status = 'PENDING_VALIDATION'
                """).param("repo", repositoryId).query(UUID.class).list();
    }

    void markVerified(UUID id, long githubRepoId, String defaultBranch) {
        jdbc.sql("""
                UPDATE app.repository SET status = 'VERIFIED', status_reason = NULL, github_repo_id = :gid,
                       default_branch = :branch, verified_at = clock_timestamp()
                WHERE id = :id
                """).param("gid", githubRepoId).param("branch", defaultBranch).param("id", id).update();
    }

    void markRepository(UUID id, String status, String reason) {
        jdbc.sql("UPDATE app.repository SET status = :status, status_reason = :reason WHERE id = :id")
                .param("status", status).param("reason", reason).param("id", id).update();
    }

    boolean pin(UUID requirementRepositoryId, String sha, String defaultBranch) {
        return jdbc.sql("""
                UPDATE app.requirement_repository SET status = 'VALIDATED', pinned_sha = :sha, default_branch = :branch,
                       validated_at = clock_timestamp()
                WHERE id = :id AND status = 'PENDING_VALIDATION'
                """).param("sha", sha).param("branch", defaultBranch).param("id", requirementRepositoryId)
                .update() == 1;
    }

    void failValidation(UUID requirementId, String code) {
        jdbc.sql("""
                UPDATE app.requirement_repository SET status = 'FAILED', failure_code = :code
                WHERE requirement_id = :id AND status = 'PENDING_VALIDATION'
                """).param("code", code).param("id", requirementId).update();
    }

    Optional<String> lastErrorCode(UUID jobId) {
        return jdbc.sql("SELECT last_error_code FROM app.job WHERE id = :id").param("id", jobId).query(String.class)
                .optional();
    }

    Optional<SnapshotRow> snapshotFor(UUID requirementRepositoryId) {
        return jdbc.sql("""
                SELECT id, commit_sha, status, storage_key, coalesce(archive_bytes, 0) FROM app.repository_snapshot
                WHERE requirement_repository_id = :id
                """).param("id", requirementRepositoryId)
                .query((rs, n) -> new SnapshotRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5))).optional();
    }

    Optional<SnapshotRow> snapshot(UUID snapshotId, boolean lock) {
        return jdbc.sql("""
                SELECT id, commit_sha, status, storage_key, coalesce(archive_bytes, 0) FROM app.repository_snapshot
                WHERE id = :id""" + (lock ? " FOR UPDATE" : ""))
                .param("id", snapshotId)
                .query((rs, n) -> new SnapshotRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5))).optional();
    }

    UUID insertSnapshot(UUID tenantId, UUID requirementRepositoryId, UUID repositoryId, String sha, String key,
                        String archiveSha, long archiveBytes, int files, int skipped, long expanded) {
        return jdbc.sql("""
                INSERT INTO app.repository_snapshot (tenant_id, requirement_repository_id, repository_id, commit_sha,
                    status, storage_key, archive_sha256, archive_bytes, file_count, skipped_count, expanded_bytes)
                VALUES (:tenant, :rr, :repo, :sha, 'STORED', :key, :archiveSha, :bytes, :files, :skipped, :expanded)
                RETURNING id
                """).param("tenant", tenantId).param("rr", requirementRepositoryId).param("repo", repositoryId)
                .param("sha", sha).param("key", key).param("archiveSha", archiveSha).param("bytes", archiveBytes)
                .param("files", files).param("skipped", skipped).param("expanded", expanded)
                .query(UUID.class).single();
    }

    void insertFailedSnapshot(UUID tenantId, UUID requirementRepositoryId, UUID repositoryId, String sha, String code) {
        jdbc.sql("""
                INSERT INTO app.repository_snapshot (tenant_id, requirement_repository_id, repository_id, commit_sha,
                                                     status, failure_code)
                VALUES (:tenant, :rr, :repo, :sha, 'FAILED', :code)
                """).param("tenant", tenantId).param("rr", requirementRepositoryId).param("repo", repositoryId)
                .param("sha", sha).param("code", code).update();
    }

    void insertFiles(UUID tenantId, UUID snapshotId, List<FileEntry> files) {
        template.batchUpdate("""
                INSERT INTO app.repository_file (tenant_id, snapshot_id, path, language, size_bytes, sha256, indexable,
                                                 skip_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, files, 500, (ps, f) -> {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, snapshotId);
                    ps.setString(3, f.path());
                    ps.setString(4, f.language());
                    ps.setLong(5, f.size());
                    ps.setString(6, f.sha256());
                    ps.setBoolean(7, f.indexable());
                    ps.setString(8, f.skipReason());
                });
    }

    void insertSymbols(UUID tenantId, UUID snapshotId, List<Symbol> symbols) {
        template.batchUpdate("""
                INSERT INTO app.code_symbol (tenant_id, snapshot_id, file_path, kind, package_name, type_name, member_name,
                                             stereotype, http_method, http_path, start_line, end_line)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, symbols, 500, (ps, s) -> {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, snapshotId);
                    ps.setString(3, s.filePath());
                    ps.setString(4, s.kind());
                    ps.setString(5, s.packageName());
                    ps.setString(6, s.typeName());
                    ps.setString(7, s.memberName());
                    ps.setString(8, s.stereotype());
                    ps.setString(9, s.httpMethod());
                    ps.setString(10, s.httpPath());
                    ps.setInt(11, s.startLine());
                    ps.setInt(12, s.endLine());
                });
    }

    void insertDependencies(UUID tenantId, UUID snapshotId, List<Dependency> dependencies) {
        template.batchUpdate("""
                INSERT INTO app.build_dependency (tenant_id, snapshot_id, file_path, build_tool, group_id, artifact_id,
                                                  version, scope)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, dependencies, 500, (ps, d) -> {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, snapshotId);
                    ps.setString(3, d.filePath());
                    ps.setString(4, d.buildTool());
                    ps.setString(5, d.groupId());
                    ps.setString(6, d.artifactId());
                    ps.setString(7, d.version());
                    ps.setString(8, d.scope());
                });
    }

    boolean markIndexed(UUID snapshotId) {
        return jdbc.sql("""
                UPDATE app.repository_snapshot SET status = 'INDEXED', indexed_at = clock_timestamp()
                WHERE id = :id AND status = 'STORED'
                """).param("id", snapshotId).update() == 1;
    }
}
