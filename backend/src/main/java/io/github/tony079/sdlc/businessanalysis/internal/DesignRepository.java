package io.github.tony079.sdlc.businessanalysis.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Technical design versions, items and decisions (GD-26). Used by the API and the worker under their own grants. */
@Repository
@Profile("!migrate & !provision")
class DesignRepository {

    record VersionRow(UUID id, UUID tenantId, UUID projectId, UUID requirementId, int versionNo, UUID brdVersionId,
                      String status, String summary, String openQuestionsJson, String contentHash,
                      String modelProvider, String modelId, String changeRequest, UUID requestedBy,
                      String failureCode, int attempts, OffsetDateTime createdAt, OffsetDateTime statusAt) {
    }

    record ItemRow(int ordinal, String itemKey, String section, String kind, String title, String detail,
                   String component, String brdRefsJson, String supportState, String evidenceJson) {
    }

    record DecisionRow(String kind, String itemKey, String note, UUID decidedBy, String decidedByName,
                       OffsetDateTime decidedAt) {
    }

    private static final String VERSION_SELECT = """
            SELECT id, tenant_id, project_id, requirement_id, version_no, brd_version_id, status, summary,
                   open_questions::text, content_hash, model_provider, model_id, change_request, requested_by,
                   failure_code, attempts, created_at, status_at
            FROM app.tdd_version
            """;

    private final JdbcClient jdbc;

    DesignRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static VersionRow version(ResultSet rs, int n) throws SQLException {
        return new VersionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getInt(5), rs.getObject(6, UUID.class), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), rs.getObject(14, UUID.class), rs.getString(15), rs.getInt(16),
                rs.getObject(17, OffsetDateTime.class), rs.getObject(18, OffsetDateTime.class));
    }

    Optional<VersionRow> find(UUID id, boolean lock) {
        return jdbc.sql(VERSION_SELECT + "WHERE id = :id" + (lock ? " FOR UPDATE" : "")).param("id", id)
                .query(DesignRepository::version).optional();
    }

    /** Newest first. */
    List<VersionRow> versions(UUID requirementId) {
        return jdbc.sql(VERSION_SELECT + "WHERE requirement_id = :r ORDER BY version_no DESC").param("r", requirementId)
                .query(DesignRepository::version).list();
    }

    Optional<VersionRow> open(UUID requirementId, boolean lock) {
        return jdbc.sql(VERSION_SELECT + "WHERE requirement_id = :r AND status IN ('GENERATING', 'IN_REVIEW', 'FAILED')"
                        + (lock ? " FOR UPDATE" : "")).param("r", requirementId)
                .query(DesignRepository::version).optional();
    }

    UUID insert(UUID tenantId, UUID projectId, UUID requirementId, UUID brdVersionId, String changeRequest,
                UUID requestedBy) {
        return jdbc.sql("""
                INSERT INTO app.tdd_version (tenant_id, project_id, requirement_id, version_no, brd_version_id, status,
                                             change_request, requested_by)
                VALUES (:t, :p, :r, (SELECT coalesce(max(version_no), 0) + 1 FROM app.tdd_version WHERE requirement_id = :r),
                        :b, 'GENERATING', :c, :u)
                RETURNING id
                """).param("t", tenantId).param("p", projectId).param("r", requirementId).param("b", brdVersionId)
                .param("c", changeRequest).param("u", requestedBy).query(UUID.class).single();
    }

    void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE app.tdd_version SET status = :s WHERE id = :id").param("s", status).param("id", id).update();
    }

    /** The author retries a failed design: a new attempt of the same version. */
    void retry(UUID id) {
        jdbc.sql("""
                UPDATE app.tdd_version SET status = 'GENERATING', attempts = attempts + 1, failure_code = NULL
                WHERE id = :id AND status = 'FAILED'
                """).param("id", id).update();
    }

    void fail(UUID id, String code) {
        jdbc.sql("""
                UPDATE app.tdd_version SET status = 'FAILED', failure_code = :c WHERE id = :id AND status = 'GENERATING'
                """).param("c", code).param("id", id).update();
    }

    void complete(UUID id, String summary, String openQuestionsJson, String contentHash, String provider,
                  String modelId) {
        jdbc.sql("""
                UPDATE app.tdd_version SET summary = :s, open_questions = :q::jsonb, content_hash = :h,
                       model_provider = :p, model_id = :m, status = 'IN_REVIEW'
                WHERE id = :id AND status = 'GENERATING'
                """).param("s", summary).param("q", openQuestionsJson).param("h", contentHash).param("p", provider)
                .param("m", modelId).param("id", id).update();
    }

    void insertItem(UUID tenantId, UUID versionId, ItemRow item) {
        jdbc.sql("""
                INSERT INTO app.tdd_item (tenant_id, tdd_version_id, ordinal, item_key, section, kind, title, detail,
                                          component, brd_refs, support_state, evidence)
                VALUES (:t, :v, :o, :k, :s, :kind, :title, :detail, :c, :refs::jsonb, :state, :ev::jsonb)
                """).param("t", tenantId).param("v", versionId).param("o", item.ordinal()).param("k", item.itemKey())
                .param("s", item.section()).param("kind", item.kind()).param("title", item.title())
                .param("detail", item.detail()).param("c", item.component()).param("refs", item.brdRefsJson())
                .param("state", item.supportState()).param("ev", item.evidenceJson()).update();
    }

    List<ItemRow> items(UUID versionId) {
        return jdbc.sql("""
                SELECT ordinal, item_key, section, kind, title, detail, component, brd_refs::text, support_state,
                       evidence::text
                FROM app.tdd_item WHERE tdd_version_id = :v ORDER BY ordinal
                """).param("v", versionId).query((rs, n) -> new ItemRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10))).list();
    }

    void insertDecision(UUID tenantId, UUID projectId, UUID requirementId, UUID versionId, String kind, String itemKey,
                        String note, UUID decidedBy) {
        jdbc.sql("""
                INSERT INTO app.tdd_decision (tenant_id, project_id, requirement_id, tdd_version_id, kind, item_key,
                                              note, decided_by)
                VALUES (:t, :p, :r, :v, :k, :i, :n, :u)
                """).param("t", tenantId).param("p", projectId).param("r", requirementId).param("v", versionId)
                .param("k", kind).param("i", itemKey).param("n", note).param("u", decidedBy).update();
    }

    List<DecisionRow> decisions(UUID versionId) {
        return jdbc.sql("""
                SELECT d.kind, d.item_key, d.note, d.decided_by, u.display_name, d.decided_at
                FROM app.tdd_decision d JOIN app.app_user u ON u.id = d.decided_by
                WHERE d.tdd_version_id = :v ORDER BY d.decided_at
                """).param("v", versionId).query((rs, n) -> new DecisionRow(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getObject(6, OffsetDateTime.class))).list();
    }

    /** The analysis input set the BRD version was produced from (its pinned code). */
    UUID brdInputSet(UUID brdVersionId) {
        return jdbc.sql("SELECT input_set_id FROM app.brd_version WHERE id = :b").param("b", brdVersionId)
                .query(UUID.class).single();
    }

    /** Active Technical Approvers of the project (GD-27). */
    List<UUID> technicalApprovers(UUID projectId) {
        return jdbc.sql("""
                SELECT m.user_id FROM app.project_member m JOIN app.app_user u ON u.id = m.user_id
                WHERE m.project_id = :p AND m.removed_at IS NULL AND 'TECHNICAL_APPROVER' = ANY (m.roles)
                  AND u.status = 'ACTIVE'
                """).param("p", projectId).query(UUID.class).list();
    }

    List<UUID> organisationAdmins() {
        return jdbc.sql("SELECT id FROM app.app_user WHERE role = 'ORG_ADMIN' AND status = 'ACTIVE'").query(UUID.class)
                .list();
    }

    UUID projectOf(UUID requirementId) {
        return jdbc.sql("SELECT project_id FROM app.requirement WHERE id = :r").param("r", requirementId)
                .query(UUID.class).single();
    }

    UUID requirementOwner(UUID requirementId) {
        return jdbc.sql("SELECT created_by FROM app.requirement WHERE id = :r").param("r", requirementId)
                .query(UUID.class).single();
    }
}
