package io.github.tony079.sdlc.businessanalysis.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Delivery plan versions, epics, stories, edits and decisions (GD-34). Used by the API and the worker. */
@Repository
@Profile("!migrate & !provision")
class PlanRepository {

    record VersionRow(UUID id, UUID tenantId, UUID projectId, UUID requirementId, int versionNo, UUID estimateVersionId,
                      String status, String settingsJson, String risksJson, String contentHash, String modelProvider,
                      String modelId, String changeRequest, UUID requestedBy, String failureCode, int attempts,
                      OffsetDateTime createdAt, UUID copiedFrom) {
    }

    record EpicRow(int ordinal, String epicKey, String title, String goal, String designRefsJson) {
    }

    record StoryRow(int ordinal, String storyKey, String epicKey, String title, String acceptanceCriteriaJson,
                    String estimateRefsJson, String brdRefsJson, String role, String priority, String dependsOnJson,
                    BigDecimal effortDays, String flagsJson, BigDecimal agentHours, boolean external,
                    BigDecimal leadDays) {

        StoryRow(int ordinal, String storyKey, String epicKey, String title, String acceptanceCriteriaJson,
                 String estimateRefsJson, String brdRefsJson, String role, String priority, String dependsOnJson,
                 BigDecimal effortDays, String flagsJson) {
            this(ordinal, storyKey, epicKey, title, acceptanceCriteriaJson, estimateRefsJson, brdRefsJson, role, priority,
                    dependsOnJson, effortDays, flagsJson, BigDecimal.ZERO, false, null);
        }
    }

    record EditRow(String storyKey, String changesJson, String reason, String editedByName, OffsetDateTime editedAt) {
    }

    record DecisionRow(String kind, String itemKey, String note, UUID decidedBy, String decidedByName,
                       OffsetDateTime decidedAt) {
    }

    private static final String VERSION_SELECT = """
            SELECT id, tenant_id, project_id, requirement_id, version_no, estimate_version_id, status, settings::text,
                   risks::text, content_hash, model_provider, model_id, change_request, requested_by, failure_code,
                   attempts, created_at, copied_from
            FROM app.plan_version
            """;

    private final JdbcClient jdbc;

    PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static VersionRow version(ResultSet rs, int n) throws SQLException {
        return new VersionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getInt(5), rs.getObject(6, UUID.class), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), rs.getObject(14, UUID.class), rs.getString(15), rs.getInt(16),
                rs.getObject(17, OffsetDateTime.class), rs.getObject(18, UUID.class));
    }

    Optional<VersionRow> find(UUID id, boolean lock) {
        return jdbc.sql(VERSION_SELECT + "WHERE id = :id" + (lock ? " FOR UPDATE" : "")).param("id", id)
                .query(PlanRepository::version).optional();
    }

    List<VersionRow> versions(UUID requirementId) {
        return jdbc.sql(VERSION_SELECT + "WHERE requirement_id = :r ORDER BY version_no DESC").param("r", requirementId)
                .query(PlanRepository::version).list();
    }

    Optional<VersionRow> open(UUID requirementId, boolean lock) {
        return jdbc.sql(VERSION_SELECT + "WHERE requirement_id = :r AND status IN ('GENERATING', 'IN_REVIEW', 'FAILED')"
                        + (lock ? " FOR UPDATE" : "")).param("r", requirementId)
                .query(PlanRepository::version).optional();
    }

    UUID insert(UUID tenantId, UUID projectId, UUID requirementId, UUID estimateVersionId, String settingsJson,
                String changeRequest, UUID requestedBy) {
        return insert(tenantId, projectId, requirementId, estimateVersionId, settingsJson, changeRequest, requestedBy,
                null);
    }

    /** A new version; {@code copiedFrom} set: the worker keeps that version's stories instead of asking the model. */
    UUID insert(UUID tenantId, UUID projectId, UUID requirementId, UUID estimateVersionId, String settingsJson,
                String changeRequest, UUID requestedBy, UUID copiedFrom) {
        return jdbc.sql("""
                INSERT INTO app.plan_version (tenant_id, project_id, requirement_id, version_no, estimate_version_id, status,
                                              settings, change_request, requested_by, copied_from)
                VALUES (:t, :p, :r, (SELECT coalesce(max(version_no), 0) + 1 FROM app.plan_version WHERE requirement_id = :r),
                        :e, 'GENERATING', :s::jsonb, :c, :u, CAST(:from AS uuid))
                RETURNING id
                """).param("t", tenantId).param("p", projectId).param("r", requirementId).param("e", estimateVersionId)
                .param("s", settingsJson).param("c", changeRequest).param("u", requestedBy).param("from", copiedFrom)
                .query(UUID.class).single();
    }

    void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE app.plan_version SET status = :s WHERE id = :id").param("s", status).param("id", id).update();
    }

    void retry(UUID id) {
        jdbc.sql("""
                UPDATE app.plan_version SET status = 'GENERATING', attempts = attempts + 1, failure_code = NULL
                WHERE id = :id AND status = 'FAILED'
                """).param("id", id).update();
    }

    void fail(UUID id, String code) {
        jdbc.sql("UPDATE app.plan_version SET status = 'FAILED', failure_code = :c WHERE id = :id AND status = 'GENERATING'")
                .param("c", code).param("id", id).update();
    }

    void complete(UUID id, String risksJson, String contentHash, String provider, String modelId) {
        jdbc.sql("""
                UPDATE app.plan_version SET risks = :r::jsonb, content_hash = :h, model_provider = :p, model_id = :m,
                       status = 'IN_REVIEW'
                WHERE id = :id AND status = 'GENERATING'
                """).param("r", risksJson).param("h", contentHash).param("p", provider).param("m", modelId)
                .param("id", id).update();
    }

    void insertEpic(UUID tenantId, UUID projectId, UUID versionId, EpicRow epic) {
        jdbc.sql("""
                INSERT INTO app.plan_epic (tenant_id, project_id, plan_version_id, ordinal, epic_key, title, goal, design_refs)
                VALUES (:t, :p, :v, :o, :k, :title, :goal, :refs::jsonb)
                """).param("t", tenantId).param("p", projectId).param("v", versionId).param("o", epic.ordinal())
                .param("k", epic.epicKey()).param("title", epic.title()).param("goal", epic.goal())
                .param("refs", epic.designRefsJson()).update();
    }

    void insertStory(UUID tenantId, UUID projectId, UUID versionId, StoryRow s) {
        jdbc.sql("""
                INSERT INTO app.plan_story (tenant_id, project_id, plan_version_id, ordinal, story_key, epic_key, title,
                                            acceptance_criteria, estimate_refs, brd_refs, role, priority, depends_on,
                                            effort_days, flags, agent_hours, external, lead_days)
                VALUES (:t, :p, :v, :o, :k, :e, :title, :ac::jsonb, :est::jsonb, :brd::jsonb, :role, :priority,
                        :deps::jsonb, :effort, :flags::jsonb, :agent, :external, CAST(:lead AS numeric))
                """).param("t", tenantId).param("p", projectId).param("v", versionId).param("o", s.ordinal())
                .param("k", s.storyKey()).param("e", s.epicKey()).param("title", s.title())
                .param("ac", s.acceptanceCriteriaJson()).param("est", s.estimateRefsJson()).param("brd", s.brdRefsJson())
                .param("role", s.role()).param("priority", s.priority()).param("deps", s.dependsOnJson())
                .param("effort", s.effortDays()).param("flags", s.flagsJson())
                .param("agent", s.agentHours() == null ? BigDecimal.ZERO : s.agentHours()).param("external", s.external())
                .param("lead", s.leadDays()).update();
    }

    List<EpicRow> epics(UUID versionId) {
        return jdbc.sql("""
                SELECT ordinal, epic_key, title, goal, design_refs::text FROM app.plan_epic
                WHERE plan_version_id = :v ORDER BY ordinal
                """).param("v", versionId).query((rs, n) -> new EpicRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5))).list();
    }

    List<StoryRow> stories(UUID versionId) {
        return jdbc.sql("""
                SELECT ordinal, story_key, epic_key, title, acceptance_criteria::text, estimate_refs::text, brd_refs::text,
                       role, priority, depends_on::text, effort_days, flags::text, agent_hours, external, lead_days
                FROM app.plan_story WHERE plan_version_id = :v ORDER BY ordinal
                """).param("v", versionId).query((rs, n) -> new StoryRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getBigDecimal(11), rs.getString(12), rs.getBigDecimal(13),
                        rs.getBoolean(14), rs.getBigDecimal(15))).list();
    }

    void insertEdit(UUID tenantId, UUID projectId, UUID versionId, String storyKey, String changesJson, String reason,
                    UUID by) {
        jdbc.sql("""
                INSERT INTO app.plan_edit (tenant_id, project_id, plan_version_id, story_key, changes, reason, edited_by)
                VALUES (:t, :p, :v, :k, :c::jsonb, :r, :u)
                """).param("t", tenantId).param("p", projectId).param("v", versionId).param("k", storyKey)
                .param("c", changesJson).param("r", reason).param("u", by).update();
    }

    /** Oldest first: later edits override earlier ones field by field. */
    List<EditRow> edits(UUID versionId) {
        return jdbc.sql("""
                SELECT e.story_key, e.changes::text, e.reason, u.display_name, e.edited_at
                FROM app.plan_edit e JOIN app.app_user u ON u.id = e.edited_by
                WHERE e.plan_version_id = :v ORDER BY e.edited_at, e.id
                """).param("v", versionId).query((rs, n) -> new EditRow(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class))).list();
    }

    /** Story key and changes of each edit, oldest first, without who made it (the worker may not read names). */
    List<String[]> editChanges(UUID versionId) {
        return jdbc.sql("""
                SELECT story_key, changes::text FROM app.plan_edit WHERE plan_version_id = :v ORDER BY edited_at, id
                """).param("v", versionId).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list();
    }

    void insertDecision(UUID tenantId, UUID projectId, UUID requirementId, UUID versionId, String kind, String itemKey,
                        String note, UUID decidedBy) {
        jdbc.sql("""
                INSERT INTO app.plan_decision (tenant_id, project_id, requirement_id, plan_version_id, kind, item_key,
                                               note, decided_by)
                VALUES (:t, :p, :r, :v, :k, :i, :n, :u)
                """).param("t", tenantId).param("p", projectId).param("r", requirementId).param("v", versionId)
                .param("k", kind).param("i", itemKey).param("n", note).param("u", decidedBy).update();
    }

    List<DecisionRow> decisions(UUID versionId) {
        return jdbc.sql("""
                SELECT d.kind, d.item_key, d.note, d.decided_by, u.display_name, d.decided_at
                FROM app.plan_decision d JOIN app.app_user u ON u.id = d.decided_by
                WHERE d.plan_version_id = :v ORDER BY d.decided_at
                """).param("v", versionId).query((rs, n) -> new DecisionRow(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getObject(6, OffsetDateTime.class))).list();
    }

    Optional<String> defaults(UUID projectId) {
        return jdbc.sql("SELECT settings::text FROM app.project_plan_defaults WHERE project_id = :p")
                .param("p", projectId).query(String.class).optional();
    }

    void saveDefaults(UUID tenantId, UUID projectId, String settingsJson, UUID by) {
        jdbc.sql("""
                INSERT INTO app.project_plan_defaults (project_id, tenant_id, settings, updated_by)
                VALUES (:p, :t, :s::jsonb, :u)
                ON CONFLICT (project_id) DO UPDATE SET settings = EXCLUDED.settings, updated_by = EXCLUDED.updated_by
                """).param("p", projectId).param("t", tenantId).param("s", settingsJson).param("u", by).update();
    }

    /** The latest approved estimate of the requirement, if any. */
    Optional<UUID> approvedEstimate(UUID requirementId) {
        return jdbc.sql("""
                SELECT id FROM app.estimate_version WHERE requirement_id = :r AND status = 'APPROVED'
                ORDER BY version_no DESC LIMIT 1
                """).param("r", requirementId).query(UUID.class).optional();
    }
}
