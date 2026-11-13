package io.github.tony079.sdlc.project.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.project.api.ProjectRole;
import io.github.tony079.sdlc.project.api.ProjectView;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Projects and memberships of the current tenant. Row-level security limits a client user to the projects they are
 * a member of (an Organisation Admin sees every project); triggers allow changes only by an Organisation Admin.
 */
@Repository
class ProjectRepository {

    /** One membership, with the member's name, email and account status (shown to Organisation Admins only). */
    record Member(UUID id, UUID projectId, String projectName, UUID userId, String displayName, String email,
                  String userStatus, ProjectRole role, OffsetDateTime addedAt) {
    }

    private static final String SELECT = """
            SELECT p.id, p.name, p.description, p.status, p.created_at, p.archived_at,
                   (SELECT m.role FROM app.project_member m WHERE m.project_id = p.id AND m.user_id = :me
                      AND m.removed_at IS NULL) AS my_role,
                   (SELECT count(*) FROM app.project_member m WHERE m.project_id = p.id AND m.removed_at IS NULL) AS members
            FROM app.project p
            """;

    private final JdbcClient jdbc;

    ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<ProjectView> findAll(UUID me, boolean includeArchived, int limit) {
        return jdbc.sql(SELECT + (includeArchived ? "" : " WHERE p.status = 'ACTIVE'")
                        + " ORDER BY p.status, lower(p.name), p.id LIMIT :limit")
                .param("me", me).param("limit", limit).query(ProjectRepository::view).list();
    }

    Optional<ProjectView> findById(UUID id, UUID me) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).param("me", me)
                .query(ProjectRepository::view).optional();
    }

    UUID insert(UUID tenantId, String name, String description, UUID createdBy) {
        return jdbc.sql("""
                INSERT INTO app.project (tenant_id, name, description, created_by)
                VALUES (:tenant, :name, :description, :createdBy) RETURNING id
                """)
                .param("tenant", tenantId).param("name", name).param("description", description)
                .param("createdBy", createdBy).query(UUID.class).single();
    }

    boolean update(UUID id, String name, String description) {
        return jdbc.sql("UPDATE app.project SET name = :name, description = :description WHERE id = :id AND status = 'ACTIVE'")
                .param("id", id).param("name", name).param("description", description).update() == 1;
    }

    boolean archive(UUID id) {
        return jdbc.sql("""
                UPDATE app.project SET status = 'ARCHIVED', archived_at = clock_timestamp()
                WHERE id = :id AND status = 'ACTIVE'
                """).param("id", id).update() == 1;
    }

    Optional<String> status(UUID id) {
        return jdbc.sql("SELECT status FROM app.project WHERE id = :id").param("id", id).query(String.class).optional();
    }

    Optional<ProjectRole> roleOf(UUID projectId, UUID userId) {
        return jdbc.sql("""
                SELECT role FROM app.project_member WHERE project_id = :project AND user_id = :user AND removed_at IS NULL
                """).param("project", projectId).param("user", userId)
                .query((rs, n) -> ProjectRole.valueOf(rs.getString(1))).optional();
    }

    List<Member> members(UUID projectId) {
        return jdbc.sql(MEMBER_SELECT + " WHERE m.project_id = :project AND m.removed_at IS NULL ORDER BY lower(u.display_name)")
                .param("project", projectId).query(ProjectRepository::member).list();
    }

    /** Active memberships of one user across the organisation (Organisation Admin view). */
    List<Member> membershipsOf(UUID userId) {
        return jdbc.sql(MEMBER_SELECT + " WHERE m.user_id = :user AND m.removed_at IS NULL ORDER BY m.added_at")
                .param("user", userId).query(ProjectRepository::member).list();
    }

    Optional<Member> member(UUID memberId) {
        return jdbc.sql(MEMBER_SELECT + " WHERE m.id = :id AND m.removed_at IS NULL")
                .param("id", memberId).query(ProjectRepository::member).optional();
    }

    UUID addMember(UUID tenantId, UUID projectId, UUID userId, ProjectRole role, UUID addedBy) {
        return jdbc.sql("""
                INSERT INTO app.project_member (tenant_id, project_id, user_id, role, added_by)
                VALUES (:tenant, :project, :user, :role, :addedBy) RETURNING id
                """)
                .param("tenant", tenantId).param("project", projectId).param("user", userId)
                .param("role", role.name()).param("addedBy", addedBy).query(UUID.class).single();
    }

    boolean changeRole(UUID memberId, ProjectRole role) {
        return jdbc.sql("UPDATE app.project_member SET role = :role WHERE id = :id AND removed_at IS NULL")
                .param("id", memberId).param("role", role.name()).update() == 1;
    }

    boolean remove(UUID memberId, UUID removedBy) {
        return jdbc.sql("""
                UPDATE app.project_member SET removed_by = :by, removed_at = clock_timestamp()
                WHERE id = :id AND removed_at IS NULL
                """).param("id", memberId).param("by", removedBy).update() == 1;
    }

    boolean userExists(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM app.app_user WHERE id = :id AND status IN ('INVITED', 'ACTIVE')")
                .param("id", userId).query(Integer.class).single() == 1;
    }

    private static final String MEMBER_SELECT = """
            SELECT m.id, m.project_id, p.name, m.user_id, u.display_name, u.email, u.status, m.role, m.added_at
            FROM app.project_member m JOIN app.app_user u ON u.id = m.user_id JOIN app.project p ON p.id = m.project_id
            """;

    private static ProjectView view(ResultSet rs, int n) throws SQLException {
        String role = rs.getString("my_role");
        return new ProjectView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), role == null ? null : ProjectRole.valueOf(role), rs.getInt("members"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("archived_at", OffsetDateTime.class));
    }

    private static Member member(ResultSet rs, int n) throws SQLException {
        return new Member(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7),
                ProjectRole.valueOf(rs.getString(8)), rs.getObject(9, OffsetDateTime.class));
    }
}
