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

/** Estimate versions, items, adjustments and decisions (GD-33). Used by the API and the worker under their own grants. */
@Repository
@Profile("!migrate & !provision")
class EstimateRepository {

    record VersionRow(UUID id, UUID tenantId, UUID projectId, UUID requirementId, int versionNo, UUID tddVersionId,
                      String status, String settingsJson, String assumptionsJson, String excludedJson,
                      String contentHash, String modelProvider, String modelId, String changeRequest, UUID requestedBy,
                      String failureCode, int attempts, OffsetDateTime createdAt) {
    }

    record ItemRow(int ordinal, String itemKey, String title, String detail, String role, String phase,
                   String designRefsJson, BigDecimal low, BigDecimal likely, BigDecimal high, String assumptionsJson,
                   String risk, String dependsOnJson, String flagsJson) {
    }

    record AdjustmentRow(String itemKey, BigDecimal low, BigDecimal likely, BigDecimal high, String reason,
                         String adjustedByName, OffsetDateTime adjustedAt) {
    }

    record DecisionRow(String kind, String itemKey, String note, UUID decidedBy, String decidedByName,
                       OffsetDateTime decidedAt) {
    }

    private static final String VERSION_SELECT = """
            SELECT id, tenant_id, project_id, requirement_id, version_no, tdd_version_id, status, settings::text,
                   assumptions::text, excluded::text, content_hash, model_provider, model_id, change_request,
                   requested_by, failure_code, attempts, created_at
            FROM app.estimate_version
            """;

    private final JdbcClient jdbc;

    EstimateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static VersionRow version(ResultSet rs, int n) throws SQLException {
        return new VersionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getInt(5), rs.getObject(6, UUID.class), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), rs.getString(14), rs.getObject(15, UUID.class), rs.getString(16), rs.getInt(17),
                rs.getObject(18, OffsetDateTime.class));
    }

    Optional<VersionRow> find(UUID id, boolean lock) {
        return jdbc.sql(VERSION_SELECT + "WHERE id = :id" + (lock ? " FOR UPDATE" : "")).param("id", id)
                .query(EstimateRepository::version).optional();
    }

    /** Newest first. */
    List<VersionRow> versions(UUID requirementId) {
        return jdbc.sql(VERSION_SELECT + "WHERE requirement_id = :r ORDER BY version_no DESC").param("r", requirementId)
                .query(EstimateRepository::version).list();
    }

    Optional<VersionRow> open(UUID requirementId, boolean lock) {
        return jdbc.sql(VERSION_SELECT + "WHERE requirement_id = :r AND status IN ('GENERATING', 'IN_REVIEW', 'FAILED')"
                        + (lock ? " FOR UPDATE" : "")).param("r", requirementId)
                .query(EstimateRepository::version).optional();
    }

    UUID insert(UUID tenantId, UUID projectId, UUID requirementId, UUID tddVersionId, String settingsJson,
                String changeRequest, UUID requestedBy) {
        return jdbc.sql("""
                INSERT INTO app.estimate_version (tenant_id, project_id, requirement_id, version_no, tdd_version_id, status,
                                                  settings, change_request, requested_by)
                VALUES (:t, :p, :r, (SELECT coalesce(max(version_no), 0) + 1 FROM app.estimate_version WHERE requirement_id = :r),
                        :d, 'GENERATING', :s::jsonb, :c, :u)
                RETURNING id
                """).param("t", tenantId).param("p", projectId).param("r", requirementId).param("d", tddVersionId)
                .param("s", settingsJson).param("c", changeRequest).param("u", requestedBy).query(UUID.class).single();
    }

    void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE app.estimate_version SET status = :s WHERE id = :id").param("s", status).param("id", id).update();
    }

    void retry(UUID id) {
        jdbc.sql("""
                UPDATE app.estimate_version SET status = 'GENERATING', attempts = attempts + 1, failure_code = NULL
                WHERE id = :id AND status = 'FAILED'
                """).param("id", id).update();
    }

    void fail(UUID id, String code) {
        jdbc.sql("""
                UPDATE app.estimate_version SET status = 'FAILED', failure_code = :c WHERE id = :id AND status = 'GENERATING'
                """).param("c", code).param("id", id).update();
    }

    void complete(UUID id, String assumptionsJson, String excludedJson, String contentHash, String provider,
                  String modelId) {
        jdbc.sql("""
                UPDATE app.estimate_version SET assumptions = :a::jsonb, excluded = :x::jsonb, content_hash = :h,
                       model_provider = :p, model_id = :m, status = 'IN_REVIEW'
                WHERE id = :id AND status = 'GENERATING'
                """).param("a", assumptionsJson).param("x", excludedJson).param("h", contentHash).param("p", provider)
                .param("m", modelId).param("id", id).update();
    }

    void insertItem(UUID tenantId, UUID projectId, UUID versionId, ItemRow item) {
        jdbc.sql("""
                INSERT INTO app.estimate_item (tenant_id, project_id, estimate_version_id, ordinal, item_key, title, detail,
                                               role, phase, design_refs, low_days, likely_days, high_days, assumptions,
                                               risk, depends_on, flags)
                VALUES (:t, :p, :v, :o, :k, :title, :detail, :role, :phase, :refs::jsonb, :low, :likely, :high,
                        :assumptions::jsonb, :risk, :deps::jsonb, :flags::jsonb)
                """).param("t", tenantId).param("p", projectId).param("v", versionId).param("o", item.ordinal())
                .param("k", item.itemKey()).param("title", item.title()).param("detail", item.detail())
                .param("role", item.role()).param("phase", item.phase()).param("refs", item.designRefsJson())
                .param("low", item.low()).param("likely", item.likely()).param("high", item.high())
                .param("assumptions", item.assumptionsJson()).param("risk", item.risk())
                .param("deps", item.dependsOnJson()).param("flags", item.flagsJson()).update();
    }

    List<ItemRow> items(UUID versionId) {
        return jdbc.sql("""
                SELECT ordinal, item_key, title, detail, role, phase, design_refs::text, low_days, likely_days, high_days,
                       assumptions::text, risk, depends_on::text, flags::text
                FROM app.estimate_item WHERE estimate_version_id = :v ORDER BY ordinal
                """).param("v", versionId).query((rs, n) -> new ItemRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8),
                        rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11), rs.getString(12), rs.getString(13),
                        rs.getString(14))).list();
    }

    void insertAdjustment(UUID tenantId, UUID projectId, UUID versionId, String itemKey, BigDecimal low,
                          BigDecimal likely, BigDecimal high, String reason, UUID adjustedBy) {
        jdbc.sql("""
                INSERT INTO app.estimate_adjustment (tenant_id, project_id, estimate_version_id, item_key, low_days,
                                                     likely_days, high_days, reason, adjusted_by)
                VALUES (:t, :p, :v, :k, :low, :likely, :high, :reason, :u)
                """).param("t", tenantId).param("p", projectId).param("v", versionId).param("k", itemKey)
                .param("low", low).param("likely", likely).param("high", high).param("reason", reason)
                .param("u", adjustedBy).update();
    }

    /** Oldest first; the latest per item is in effect. */
    List<AdjustmentRow> adjustments(UUID versionId) {
        return jdbc.sql("""
                SELECT a.item_key, a.low_days, a.likely_days, a.high_days, a.reason, u.display_name, a.adjusted_at
                FROM app.estimate_adjustment a JOIN app.app_user u ON u.id = a.adjusted_by
                WHERE a.estimate_version_id = :v ORDER BY a.adjusted_at, a.id
                """).param("v", versionId).query((rs, n) -> new AdjustmentRow(rs.getString(1), rs.getBigDecimal(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5), rs.getString(6),
                        rs.getObject(7, OffsetDateTime.class))).list();
    }

    /** Adjustments without who made them: for the worker, which may not read people's names. Oldest first. */
    List<AdjustmentRow> adjustmentFigures(UUID versionId) {
        return jdbc.sql("""
                SELECT item_key, low_days, likely_days, high_days FROM app.estimate_adjustment
                WHERE estimate_version_id = :v ORDER BY adjusted_at, id
                """).param("v", versionId).query((rs, n) -> new AdjustmentRow(rs.getString(1), rs.getBigDecimal(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4), null, null, null)).list();
    }

    void insertDecision(UUID tenantId, UUID projectId, UUID requirementId, UUID versionId, String kind, String itemKey,
                        String note, UUID decidedBy) {
        jdbc.sql("""
                INSERT INTO app.estimate_decision (tenant_id, project_id, requirement_id, estimate_version_id, kind,
                                                   item_key, note, decided_by)
                VALUES (:t, :p, :r, :v, :k, :i, :n, :u)
                """).param("t", tenantId).param("p", projectId).param("r", requirementId).param("v", versionId)
                .param("k", kind).param("i", itemKey).param("n", note).param("u", decidedBy).update();
    }

    List<DecisionRow> decisions(UUID versionId) {
        return jdbc.sql("""
                SELECT d.kind, d.item_key, d.note, d.decided_by, u.display_name, d.decided_at
                FROM app.estimate_decision d JOIN app.app_user u ON u.id = d.decided_by
                WHERE d.estimate_version_id = :v ORDER BY d.decided_at
                """).param("v", versionId).query((rs, n) -> new DecisionRow(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getObject(6, OffsetDateTime.class))).list();
    }

    Optional<String> defaults(UUID projectId) {
        return jdbc.sql("SELECT settings::text FROM app.project_estimate_defaults WHERE project_id = :p")
                .param("p", projectId).query(String.class).optional();
    }

    void saveDefaults(UUID tenantId, UUID projectId, String settingsJson, UUID by) {
        jdbc.sql("""
                INSERT INTO app.project_estimate_defaults (project_id, tenant_id, settings, updated_by)
                VALUES (:p, :t, :s::jsonb, :u)
                ON CONFLICT (project_id) DO UPDATE SET settings = EXCLUDED.settings, updated_by = EXCLUDED.updated_by
                """).param("p", projectId).param("t", tenantId).param("s", settingsJson).param("u", by).update();
    }

    /** The latest approved design of the requirement, if any. */
    Optional<UUID> approvedDesign(UUID requirementId) {
        return jdbc.sql("""
                SELECT id FROM app.tdd_version WHERE requirement_id = :r AND status = 'APPROVED'
                ORDER BY version_no DESC LIMIT 1
                """).param("r", requirementId).query(UUID.class).optional();
    }

    /** The approved BRD version a design was made from. */
    Optional<UUID> brdVersionOfDesign(UUID tddVersionId) {
        return jdbc.sql("SELECT brd_version_id FROM app.tdd_version WHERE id = :d").param("d", tddVersionId)
                .query(UUID.class).optional();
    }

    boolean isEstimator(UUID projectId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT app.is_estimator(:p)").param("p", projectId)
                .query(Boolean.class).single());
    }

    /** Active members of the project holding the role. */
    List<UUID> membersWithRole(UUID projectId, String role) {
        return jdbc.sql("""
                SELECT m.user_id FROM app.project_member m JOIN app.app_user u ON u.id = m.user_id
                WHERE m.project_id = :p AND m.removed_at IS NULL AND :role = ANY (m.roles) AND u.status = 'ACTIVE'
                """).param("p", projectId).param("role", role).query(UUID.class).list();
    }
}
