package io.github.tony079.sdlc.requirement.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.requirement.api.RequirementView.Content;
import io.github.tony079.sdlc.requirement.api.RequirementView.Revision;
import io.github.tony079.sdlc.requirement.api.RequirementView.RevisionSummary;
import io.github.tony079.sdlc.requirement.api.RequirementView.Summary;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requirements and their immutable revisions. RLS restricts every statement to the transaction's tenant; owner
 * scoping (PRD section 4: client users see only their own requirements) is applied by every lookup here through
 * {@code created_by}. Revisions are only ever inserted (the role cannot update or delete them).
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class RequirementRepository {

    private static final String REVISION_COLUMNS = """
            rev.kind, rev.title, rev.description, rev.business_objective, rev.expected_outcomes,
            rev.acceptance_criteria, rev.repository_url, rev.constraints, rev.integrations""";

    private final JdbcClient jdbc;

    RequirementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(UUID id, String key, UUID projectId, UUID createdBy, int currentRevisionNo, long version,
               OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    record Cursor(OffsetDateTime updatedAt, UUID id) {
    }

    boolean projectExists(UUID projectId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app.project WHERE id = :id)")
                .param("id", projectId).query(Boolean.class).single();
    }

    void insertRequirement(UUID id, UUID tenantId, UUID projectId, String key, UUID createdBy) {
        jdbc.sql("""
                INSERT INTO app.requirement (id, tenant_id, project_id, key, created_by, current_revision_no)
                VALUES (:id, :tenant, :project, :key, :createdBy, 1)
                """)
                .param("id", id).param("tenant", tenantId).param("project", projectId).param("key", key)
                .param("createdBy", createdBy)
                .update();
    }

    void insertRevision(UUID tenantId, UUID requirementId, int revisionNo, RequirementFields fields, UUID createdBy) {
        insertRevision(tenantId, requirementId, revisionNo, fields, createdBy, null);
    }

    /** {@code convertedByStaff}: the Business Reviewer who converted a manual-intake requirement (T04), else null. */
    void insertRevision(UUID tenantId, UUID requirementId, int revisionNo, RequirementFields fields, UUID createdBy,
                        UUID convertedByStaff) {
        jdbc.sql("""
                INSERT INTO app.requirement_revision (tenant_id, requirement_id, revision_no, kind, title, description,
                    business_objective, expected_outcomes, acceptance_criteria, repository_url, constraints,
                    integrations, content_hash, created_by, converted_by_staff)
                VALUES (:tenant, :requirement, :revisionNo, :kind, :title, :description, :businessObjective,
                    :expectedOutcomes, :acceptanceCriteria, :repositoryUrl, :constraints, :integrations,
                    :contentHash, :createdBy, :staff)
                """).param("staff", convertedByStaff)
                .param("tenant", tenantId).param("requirement", requirementId).param("revisionNo", revisionNo)
                .param("kind", fields.kind()).param("title", fields.title()).param("description", fields.description())
                .param("businessObjective", fields.businessObjective())
                .param("expectedOutcomes", fields.expectedOutcomes())
                .param("acceptanceCriteria", fields.acceptanceCriteria())
                .param("repositoryUrl", fields.repositoryUrl()).param("constraints", fields.constraints())
                .param("integrations", fields.integrations()).param("contentHash", fields.contentHash())
                .param("createdBy", createdBy)
                .update();
    }

    Optional<UUID> projectOf(UUID id) {
        return jdbc.sql("SELECT project_id FROM app.requirement WHERE id = :id").param("id", id).query(UUID.class)
                .optional();
    }

    /** Any requirement of the current tenant (RLS); for staff paths that are authorised by capability. */
    Optional<Row> find(UUID id) {
        return jdbc.sql("""
                SELECT id, key, project_id, created_by, current_revision_no, version, created_at, updated_at
                FROM app.requirement WHERE id = :id
                """)
                .param("id", id)
                .query(RequirementRepository::row)
                .optional();
    }

    /** C-06: the latest repository link the Client Approver authorised (or staff registered), if any. */
    Optional<String> authorisedRepositoryUrl(UUID requirementId) {
        return jdbc.sql("""
                SELECT repository_url FROM app.requirement_repository_link
                WHERE requirement_id = :r AND status IN ('AUTHORIZED', 'REGISTERED')
                ORDER BY authorized_at DESC LIMIT 1
                """).param("r", requirementId).query(String.class).optional();
    }

    record ManualIntakeEntry(UUID id, String key, String title, int currentRevisionNo, OffsetDateTime enteredAt,
                             boolean repositoryLinkAuthorised) {
    }

    List<ManualIntakeEntry> manualIntake() {
        return jdbc.sql("""
                SELECT r.id, r.key, v.title, r.current_revision_no, t.occurred_at,
                       EXISTS (SELECT 1 FROM app.requirement_repository_link l WHERE l.requirement_id = r.id
                               AND l.status IN ('AUTHORIZED', 'REGISTERED')) AS linked
                FROM app.requirement r
                JOIN app.requirement_revision v ON v.requirement_id = r.id AND v.revision_no = r.current_revision_no
                JOIN app.workflow_instance i ON i.requirement_id = r.id
                JOIN app.workflow_transition t ON t.instance_id = i.id AND t.seq = i.last_seq
                WHERE i.projected_state = 'MANUAL_INTAKE'
                ORDER BY t.occurred_at
                """).query((rs, n) -> new ManualIntakeEntry(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getInt(4), rs.getObject(5, OffsetDateTime.class), rs.getBoolean(6))).list();
    }

    Optional<Row> findOwned(UUID id, UUID owner) {
        return jdbc.sql("""
                SELECT id, key, project_id, created_by, current_revision_no, version, created_at, updated_at
                FROM app.requirement WHERE id = :id AND created_by = :owner
                """)
                .param("id", id).param("owner", owner)
                .query(RequirementRepository::row)
                .optional();
    }

    /**
     * Optimistic lock: advances the version (and optionally the current revision) only if the caller saw the
     * current version. Concurrent writers with the same expected version: one updates, the other sees 0 rows
     * after the first commits (verified, V-M2-2).
     */
    boolean advance(UUID id, long expectedVersion, int newRevisionNo) {
        return jdbc.sql("""
                UPDATE app.requirement
                SET version = version + 1, current_revision_no = :revision, updated_at = clock_timestamp()
                WHERE id = :id AND version = :expected
                """)
                .param("id", id).param("expected", expectedVersion).param("revision", newRevisionNo)
                .update() == 1;
    }

    Optional<Revision> findRevision(UUID requirementId, int revisionNo) {
        return jdbc.sql("SELECT rev.requirement_id, rev.revision_no, " + REVISION_COLUMNS
                        + ", rev.content_hash, rev.created_by, rev.created_at FROM app.requirement_revision rev "
                        + "WHERE rev.requirement_id = :requirement AND rev.revision_no = :revisionNo")
                .param("requirement", requirementId).param("revisionNo", revisionNo)
                .query((rs, n) -> new Revision(rs.getObject("requirement_id", UUID.class), rs.getInt("revision_no"),
                        content(rs), rs.getString("content_hash"), rs.getObject("created_by", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }

    List<RevisionSummary> revisions(UUID requirementId) {
        return jdbc.sql("""
                SELECT revision_no, content_hash, created_at FROM app.requirement_revision
                WHERE requirement_id = :requirement ORDER BY revision_no
                """)
                .param("requirement", requirementId)
                .query((rs, n) -> new RevisionSummary(rs.getInt("revision_no"), rs.getString("content_hash"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    /**
     * The owner's requirements, newest first, keyset-paginated on (updated_at, id). Reads the workflow projection
     * for the state filter; writes to workflow tables happen only in the workflow module.
     */
    List<Summary> listOwned(UUID owner, WorkflowState state, UUID projectId, Cursor after, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.key, rev.title, wi.projected_state, r.project_id, r.current_revision_no, r.updated_at
                FROM app.requirement r
                JOIN app.requirement_revision rev ON rev.requirement_id = r.id AND rev.revision_no = r.current_revision_no
                JOIN app.workflow_instance wi ON wi.requirement_id = r.id
                WHERE r.created_by = :owner
                """);
        if (state != null) {
            sql.append(" AND wi.projected_state = :state");
        }
        if (projectId != null) {
            sql.append(" AND r.project_id = :project");
        }
        if (after != null) {
            sql.append(" AND (r.updated_at, r.id) < (:afterUpdated, :afterId)");
        }
        sql.append(" ORDER BY r.updated_at DESC, r.id DESC LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("owner", owner).param("limit", limit);
        if (state != null) {
            spec = spec.param("state", state.name());
        }
        if (projectId != null) {
            spec = spec.param("project", projectId);
        }
        if (after != null) {
            spec = spec.param("afterUpdated", after.updatedAt()).param("afterId", after.id());
        }
        return spec.query((rs, n) -> new Summary(rs.getObject("id", UUID.class), rs.getString("key"),
                        rs.getString("title"), WorkflowState.valueOf(rs.getString("projected_state")),
                        rs.getObject("project_id", UUID.class), rs.getInt("current_revision_no"),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("key"), rs.getObject("project_id", UUID.class),
                rs.getObject("created_by", UUID.class), rs.getInt("current_revision_no"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static Content content(ResultSet rs) throws SQLException {
        return new Content(rs.getString("kind"), rs.getString("title"), rs.getString("description"),
                rs.getString("business_objective"), rs.getString("expected_outcomes"),
                rs.getString("acceptance_criteria"), rs.getString("repository_url"), rs.getString("constraints"),
                rs.getString("integrations"));
    }
}
