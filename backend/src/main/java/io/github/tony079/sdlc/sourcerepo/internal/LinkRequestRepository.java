package io.github.tony079.sdlc.sourcerepo.internal;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Data access for C-18 link requests and client repository authorisations. Tenant rows only (forced RLS). */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class LinkRequestRepository {

    record LinkRequest(UUID id, UUID requirementId, UUID createdBy, String status, boolean expired,
                       OffsetDateTime expiresAt) {
    }

    record Link(UUID id, UUID requirementId, String repositoryUrl, String githubOwner, String githubName,
                String status, OffsetDateTime authorizedAt) {
    }

    private final JdbcClient jdbc;

    LinkRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String REQUEST_COLUMNS =
            "id, requirement_id, created_by, status, expires_at <= clock_timestamp() AS expired, expires_at";

    private static LinkRequest mapRequest(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new LinkRequest(rs.getObject("id", UUID.class), rs.getObject("requirement_id", UUID.class),
                rs.getObject("created_by", UUID.class), rs.getString("status"), rs.getBoolean("expired"),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    Optional<LinkRequest> findByTokenHash(String tokenHash) {
        return jdbc.sql("SELECT " + REQUEST_COLUMNS + " FROM app.repository_link_request WHERE token_hash = :hash")
                .param("hash", tokenHash).query(LinkRequestRepository::mapRequest).optional();
    }

    Optional<LinkRequest> lockById(UUID id) {
        return jdbc.sql("SELECT " + REQUEST_COLUMNS + " FROM app.repository_link_request WHERE id = :id FOR UPDATE")
                .param("id", id).query(LinkRequestRepository::mapRequest).optional();
    }

    Optional<LinkRequest> findById(UUID id) {
        return jdbc.sql("SELECT " + REQUEST_COLUMNS + " FROM app.repository_link_request WHERE id = :id")
                .param("id", id).query(LinkRequestRepository::mapRequest).optional();
    }

    Optional<LinkRequest> pendingFor(UUID requirementId) {
        return jdbc.sql("SELECT " + REQUEST_COLUMNS + """
                 FROM app.repository_link_request WHERE requirement_id = :requirement AND status = 'PENDING'
                """).param("requirement", requirementId).query(LinkRequestRepository::mapRequest).optional();
    }

    UUID insertRequest(UUID tenantId, UUID requirementId, UUID createdBy, String tokenHash, long hours) {
        return jdbc.sql("""
                INSERT INTO app.repository_link_request (tenant_id, requirement_id, created_by, token_hash, operation,
                                                         intended_role, expires_at)
                VALUES (:tenant, :requirement, :createdBy, :hash, 'ATTACH_REPOSITORY_LINK', 'CLIENT_APPROVER',
                        clock_timestamp() + make_interval(hours => :hours))
                RETURNING id
                """).param("tenant", tenantId).param("requirement", requirementId).param("createdBy", createdBy)
                .param("hash", tokenHash).param("hours", (int) hours).query(UUID.class).single();
    }

    void revokePending(UUID requirementId) {
        jdbc.sql("""
                UPDATE app.repository_link_request SET status = 'REVOKED', revoked_at = clock_timestamp()
                WHERE requirement_id = :requirement AND status = 'PENDING'
                """).param("requirement", requirementId).update();
    }

    boolean revoke(UUID id) {
        return jdbc.sql("""
                UPDATE app.repository_link_request SET status = 'REVOKED', revoked_at = clock_timestamp()
                WHERE id = :id AND status = 'PENDING'
                """).param("id", id).update() == 1;
    }

    void markUsed(UUID id, UUID usedBy) {
        jdbc.sql("""
                UPDATE app.repository_link_request SET status = 'USED', used_at = clock_timestamp(), used_by = :user
                WHERE id = :id AND status = 'PENDING'
                """).param("user", usedBy).param("id", id).update();
    }

    Optional<Link> currentLink(UUID requirementId) {
        return jdbc.sql("""
                SELECT id, requirement_id, repository_url, github_owner, github_name, status, authorized_at
                FROM app.requirement_repository_link
                WHERE requirement_id = :requirement AND status IN ('AUTHORIZED', 'REGISTERED')
                """).param("requirement", requirementId)
                .query((rs, n) -> new Link(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class)))
                .optional();
    }

    void supersede(UUID linkId) {
        jdbc.sql("""
                UPDATE app.requirement_repository_link SET status = 'SUPERSEDED', status_at = clock_timestamp()
                WHERE id = :id AND status = 'AUTHORIZED'
                """).param("id", linkId).update();
    }

    UUID insertLink(UUID tenantId, UUID requirementId, UUID requestId, String url, String owner, String name,
                    UUID authorizedBy) {
        return jdbc.sql("""
                INSERT INTO app.requirement_repository_link (tenant_id, requirement_id, request_id, repository_url,
                                                             github_owner, github_name, authorized_by)
                VALUES (:tenant, :requirement, :request, :url, :owner, :name, :by)
                RETURNING id
                """).param("tenant", tenantId).param("requirement", requirementId).param("request", requestId)
                .param("url", url).param("owner", owner).param("name", name).param("by", authorizedBy)
                .query(UUID.class).single();
    }
}
