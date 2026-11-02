package io.github.tony079.sdlc.project.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.project.api.ProjectView;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects of the current tenant. Queries carry no tenant filter of their own on purpose: RLS restricts every
 * statement to the transaction's tenant, and inserts are checked by the policy's WITH CHECK clause.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class ProjectRepository {

    private static final RowMapper<ProjectView> MAPPER = (rs, n) -> new ProjectView(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<ProjectView> findAll(int limit) {
        return jdbc.sql("SELECT id, name, created_at FROM app.project ORDER BY created_at DESC, id LIMIT :limit")
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    Optional<ProjectView> findById(UUID id) {
        return jdbc.sql("SELECT id, name, created_at FROM app.project WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    ProjectView insert(UUID tenantId, String name, UUID createdBy) {
        return jdbc.sql("""
                INSERT INTO app.project (tenant_id, name, created_by) VALUES (:tenant, :name, :createdBy)
                RETURNING id, name, created_at
                """)
                .param("tenant", tenantId)
                .param("name", name)
                .param("createdBy", createdBy)
                .query(MAPPER)
                .single();
    }
}
