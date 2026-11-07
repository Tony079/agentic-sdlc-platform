package io.github.tony079.sdlc.sourcerepo.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** API-side repository registration data access (tenant rows, forced RLS). */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class RegistrationRepository {

    record AuthorizedLink(UUID id, UUID requirementId, String repositoryUrl, String owner, String name, String status) {
    }

    record PendingLink(UUID linkId, String repositoryUrl, UUID projectId, OffsetDateTime authorizedAt) {
    }

    record RepositorySummary(UUID id, String owner, String name, String repositoryUrl, String status,
                             String defaultBranch, OffsetDateTime registeredAt, OffsetDateTime verifiedAt) {
    }

    record Validation(String status, String pinnedSha, String defaultBranch, String failureCode,
                      String snapshotStatus, Integer fileCount) {
    }

    private final JdbcClient jdbc;

    RegistrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AuthorizedLink> lockLink(UUID linkId) {
        return jdbc.sql("""
                SELECT id, requirement_id, repository_url, github_owner, github_name, status
                FROM app.requirement_repository_link WHERE id = :id FOR UPDATE
                """).param("id", linkId)
                .query((rs, n) -> new AuthorizedLink(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional();
    }

    List<PendingLink> pendingLinks() {
        return jdbc.sql("""
                SELECT l.id, l.repository_url, r.project_id, l.authorized_at
                FROM app.requirement_repository_link l JOIN app.requirement r ON r.id = l.requirement_id
                WHERE l.status = 'AUTHORIZED' ORDER BY l.authorized_at
                """).query((rs, n) -> new PendingLink(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getObject(4, OffsetDateTime.class))).list();
    }

    Optional<UUID> findRepository(String owner, String name) {
        return jdbc.sql("""
                SELECT id FROM app.repository WHERE lower(github_owner) = lower(:owner) AND lower(github_name) = lower(:name)
                """).param("owner", owner).param("name", name).query(UUID.class).optional();
    }

    UUID insertRepository(UUID tenantId, String owner, String name, String url, UUID staffId) {
        return jdbc.sql("""
                INSERT INTO app.repository (tenant_id, github_owner, github_name, repository_url, registered_by)
                VALUES (:tenant, :owner, :name, :url, :staff) RETURNING id
                """).param("tenant", tenantId).param("owner", owner).param("name", name).param("url", url)
                .param("staff", staffId).query(UUID.class).single();
    }

    void linkProject(UUID tenantId, UUID projectId, UUID repositoryId) {
        jdbc.sql("""
                INSERT INTO app.project_repository (tenant_id, project_id, repository_id) VALUES (:tenant, :project, :repo)
                ON CONFLICT DO NOTHING
                """).param("tenant", tenantId).param("project", projectId).param("repo", repositoryId).update();
    }

    boolean requirementRegistered(UUID requirementId) {
        return jdbc.sql("SELECT count(*) FROM app.requirement_repository WHERE requirement_id = :id")
                .param("id", requirementId).query(Integer.class).single() > 0;
    }

    UUID insertRequirementRepository(UUID tenantId, UUID requirementId, UUID repositoryId, UUID linkId) {
        return jdbc.sql("""
                INSERT INTO app.requirement_repository (tenant_id, requirement_id, repository_id, link_id)
                VALUES (:tenant, :requirement, :repo, :link) RETURNING id
                """).param("tenant", tenantId).param("requirement", requirementId).param("repo", repositoryId)
                .param("link", linkId).query(UUID.class).single();
    }

    void markLinkRegistered(UUID linkId) {
        jdbc.sql("""
                UPDATE app.requirement_repository_link SET status = 'REGISTERED', status_at = clock_timestamp()
                WHERE id = :id AND status = 'AUTHORIZED'
                """).param("id", linkId).update();
    }

    String repositoryStatus(UUID repositoryId) {
        return jdbc.sql("SELECT status FROM app.repository WHERE id = :id").param("id", repositoryId)
                .query(String.class).single();
    }

    List<RepositorySummary> projectRepositories(UUID projectId) {
        return jdbc.sql("""
                SELECT r.id, r.github_owner, r.github_name, r.repository_url, r.status, r.default_branch, r.registered_at,
                       r.verified_at
                FROM app.project_repository p JOIN app.repository r ON r.id = p.repository_id
                WHERE p.project_id = :project ORDER BY r.registered_at
                """).param("project", projectId)
                .query((rs, n) -> new RepositorySummary(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class),
                        rs.getObject(8, OffsetDateTime.class)))
                .list();
    }

    Optional<Validation> validation(UUID requirementId) {
        return jdbc.sql("""
                SELECT rr.status, rr.pinned_sha, rr.default_branch, rr.failure_code, s.status, s.file_count
                FROM app.requirement_repository rr LEFT JOIN app.repository_snapshot s ON s.requirement_repository_id = rr.id
                WHERE rr.requirement_id = :requirement
                """).param("requirement", requirementId)
                .query((rs, n) -> new Validation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), (Integer) rs.getObject(6)))
                .optional();
    }
}
