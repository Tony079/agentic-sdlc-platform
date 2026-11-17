package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.EvidenceView;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.ItemView;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.NotificationView;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.ReviewQueueEntry;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.VersionView;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Review, approval, release and client-view data access (tenant rows, forced RLS). */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class ReviewRepository {

    record VersionRow(UUID id, UUID requirementId, int versionNo, String status, String contentHash, String inputHash,
                      UUID inputSetId, String correctionsJson) {
    }

    private final JdbcClient jdbc;

    ReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String VERSION_SELECT = """
            SELECT v.id, v.requirement_id, v.version_no, v.status, v.content_hash, v.input_hash, v.input_set_id,
                   s.corrections::text
            FROM app.brd_version v JOIN app.analysis_input_set s ON s.id = v.input_set_id
            """;

    private static VersionRow versionRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new VersionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8));
    }

    Optional<VersionRow> version(UUID id, boolean lock) {
        return jdbc.sql(VERSION_SELECT + "WHERE v.id = :id" + (lock ? " FOR UPDATE OF v" : "")).param("id", id)
                .query(ReviewRepository::versionRow).optional();
    }

    Optional<VersionRow> versionByNumber(UUID requirementId, int versionNo) {
        return jdbc.sql(VERSION_SELECT + "WHERE v.requirement_id = :r AND v.version_no = :n").param("r", requirementId)
                .param("n", versionNo).query(ReviewRepository::versionRow).optional();
    }

    List<BrdViews.VersionRef> versionsOf(UUID requirementId) {
        return jdbc.sql("""
                SELECT id, version_no, status, content_hash FROM app.brd_version
                WHERE requirement_id = :r ORDER BY version_no DESC
                """).param("r", requirementId).query((rs, n) -> new BrdViews.VersionRef(rs.getObject(1, UUID.class),
                rs.getInt(2), rs.getString(3), rs.getString(4))).list();
    }

    Optional<VersionRow> latestWithStatus(UUID requirementId, Set<String> statuses) {
        return jdbc.sql(VERSION_SELECT + "WHERE v.requirement_id = :r AND v.status IN (:s) ORDER BY v.version_no DESC LIMIT 1")
                .param("r", requirementId).param("s", statuses).query(ReviewRepository::versionRow).optional();
    }

    List<ReviewQueueEntry> queue() {
        return jdbc.sql("""
                SELECT r.id, r.key, rv.title, v.id, v.version_no,
                       (SELECT count(*) FROM app.brd_item i WHERE i.brd_version_id = v.id AND i.support_state = 'FLAGGED'),
                       (SELECT count(*) FROM app.brd_item i WHERE i.brd_version_id = v.id AND i.support_state = 'FLAGGED'
                          AND NOT EXISTS (SELECT 1 FROM app.brd_flag_ack a WHERE a.brd_version_id = v.id
                                          AND a.item_key = i.item_key)),
                       v.created_at
                FROM app.brd_version v
                JOIN app.requirement r ON r.id = v.requirement_id
                JOIN app.requirement_revision rv ON rv.requirement_id = r.id AND rv.revision_no = r.current_revision_no
                JOIN app.workflow_instance w ON w.requirement_id = r.id
                WHERE v.status = 'IN_REVIEW' AND w.projected_state = 'INTERNAL_REVIEW'
                ORDER BY v.created_at
                """).query((rs, n) -> new ReviewQueueEntry(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        rs.getObject(8, OffsetDateTime.class))).list();
    }

    String requirementKey(UUID requirementId) {
        return jdbc.sql("SELECT key FROM app.requirement WHERE id = :id").param("id", requirementId).query(String.class)
                .single();
    }

    /** Full version with items and evidence; quotes only when {@code withExcerpts} (reviewers, AC-EXT-29). */
    VersionView view(UUID versionId, boolean withExcerpts) {
        var header = jdbc.sql("""
                SELECT id, requirement_id, version_no, status, content_hash, input_hash, understanding,
                       excluded_documents::text, model_provider, model_id, created_at
                FROM app.brd_version WHERE id = :id
                """).param("id", versionId).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getObject(11, OffsetDateTime.class)}).single();
        Map<UUID, List<EvidenceView>> evidence = new HashMap<>();
        jdbc.sql("""
                SELECT e.brd_item_id, e.type, e.path, e.start_line, e.end_line, e.commit_sha, e.document_version_id,
                       d.file_name, e.chunk_ordinal, e.chunk_label, e.quote, e.support_passed
                FROM app.brd_item_evidence e JOIN app.brd_item i ON i.id = e.brd_item_id
                LEFT JOIN app.document_version d ON d.id = e.document_version_id
                WHERE i.brd_version_id = :v ORDER BY e.id
                """).param("v", versionId).query((rs, n) -> {
                    evidence.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(new EvidenceView(
                            rs.getString(2), rs.getString(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5),
                            rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8), (Integer) rs.getObject(9),
                            rs.getString(10), withExcerpts ? rs.getString(11) : null,
                            withExcerpts ? rs.getBoolean(12) : null));
                    return null;
                }).list();
        List<ItemView> items = jdbc.sql("""
                SELECT i.id, i.item_key, i.ordinal, i.kind, i.proposed_kind, i.section, i.statement, i.risk, i.component,
                       i.support_state, i.rejected_citations,
                       EXISTS (SELECT 1 FROM app.brd_flag_ack a WHERE a.brd_version_id = i.brd_version_id
                               AND a.item_key = i.item_key)
                FROM app.brd_item i WHERE i.brd_version_id = :v ORDER BY i.ordinal
                """).param("v", versionId)
                .query((rs, n) -> new ItemView(rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getInt(11), rs.getBoolean(12),
                        evidence.getOrDefault(rs.getObject(1, UUID.class), List.of())))
                .list();
        return new VersionView((UUID) header[0], (UUID) header[1], (Integer) header[2], (String) header[3],
                (String) header[4], (String) header[5], (String) header[6], (String) header[7], (String) header[8],
                (String) header[9], (OffsetDateTime) header[10], items);
    }

    record InputSetRef(int revisionNo, String commitSha) {
    }

    InputSetRef inputSetOf(UUID versionId) {
        return jdbc.sql("""
                SELECT s.revision_no, s.commit_sha FROM app.brd_version v JOIN app.analysis_input_set s ON s.id = v.input_set_id
                WHERE v.id = :v
                """).param("v", versionId).query((rs, n) -> new InputSetRef(rs.getInt(1), rs.getString(2))).single();
    }

    int openBlockingClarifications(UUID requirementId) {
        return jdbc.sql("""
                SELECT count(*) FROM app.clarification WHERE requirement_id = :r AND blocking
                  AND status IN ('OPEN', 'NEEDS_INFO', 'ANSWERED')
                """).param("r", requirementId).query(Integer.class).single();
    }

    boolean itemExists(UUID versionId, String itemKey) {
        return jdbc.sql("SELECT count(*) FROM app.brd_item WHERE brd_version_id = :v AND item_key = :k")
                .param("v", versionId).param("k", itemKey).query(Integer.class).single() > 0;
    }

    boolean isFlagged(UUID versionId, String itemKey) {
        return jdbc.sql("""
                SELECT count(*) FROM app.brd_item WHERE brd_version_id = :v AND item_key = :k AND support_state = 'FLAGGED'
                """).param("v", versionId).param("k", itemKey).query(Integer.class).single() > 0;
    }

    void acknowledge(UUID tenantId, UUID versionId, String itemKey, UUID staffId, String note) {
        jdbc.sql("""
                INSERT INTO app.brd_flag_ack (tenant_id, brd_version_id, item_key, staff_id, note)
                VALUES (:t, :v, :k, :s, :n) ON CONFLICT DO NOTHING
                """).param("t", tenantId).param("v", versionId).param("k", itemKey).param("s", staffId).param("n", note)
                .update();
    }

    int unacknowledgedFlags(UUID versionId) {
        return jdbc.sql("""
                SELECT count(*) FROM app.brd_item i WHERE i.brd_version_id = :v AND i.support_state = 'FLAGGED'
                  AND NOT EXISTS (SELECT 1 FROM app.brd_flag_ack a WHERE a.brd_version_id = i.brd_version_id
                                  AND a.item_key = i.item_key)
                """).param("v", versionId).query(Integer.class).single();
    }

    void insertApproval(UUID tenantId, VersionRow version, UUID staffId, String decision, String reason, String notes) {
        jdbc.sql("""
                INSERT INTO app.approval (tenant_id, approval_type, brd_version_id, content_hash, input_set_hash,
                                          approver_staff_id, decision, reason, notes)
                VALUES (:t, 'BRD_REVIEW', :v, :content, :input, :staff, :decision, :reason, CAST(:notes AS jsonb))
                """).param("t", tenantId).param("v", version.id()).param("content", version.contentHash())
                .param("input", version.inputHash()).param("staff", staffId).param("decision", decision)
                .param("reason", reason).param("notes", notes).update();
    }

    /** The latest revision notes: a Garuda reviewer's (staff) or, since GD-24, the client's change request. */
    Optional<String> latestRevisionNotes(UUID requirementId) {
        return jdbc.sql("""
                SELECT notes FROM (
                  SELECT a.notes::text AS notes, a.decided_at AS at FROM app.approval a
                  JOIN app.brd_version v ON v.id = a.brd_version_id
                  WHERE v.requirement_id = :r AND a.decision = 'REVISION_REQUESTED'
                  UNION ALL
                  SELECT jsonb_build_array(jsonb_build_object('source', 'CLIENT', 'instruction', d.note))::text, d.decided_at
                  FROM app.brd_client_decision d WHERE d.requirement_id = :r AND d.kind = 'CHANGES_REQUESTED'
                ) n ORDER BY at DESC LIMIT 1
                """).param("r", requirementId).query(String.class).optional();
    }

    // --- GD-24: the client's review ---------------------------------------------------------------------------

    record ClientDecision(String kind, String itemKey, UUID decidedBy, String decidedByName, String note,
                          java.time.OffsetDateTime decidedAt) {
    }

    List<ClientDecision> clientDecisions(UUID versionId) {
        return jdbc.sql("""
                SELECT d.kind, d.item_key, d.decided_by, u.display_name, d.note, d.decided_at
                FROM app.brd_client_decision d JOIN app.app_user u ON u.id = d.decided_by
                WHERE d.brd_version_id = :v ORDER BY d.decided_at
                """).param("v", versionId).query((rs, n) -> new ClientDecision(rs.getString(1), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                        rs.getObject(6, java.time.OffsetDateTime.class))).list();
    }

    /** Worker-safe (no user names): whether a BRD Approver approved this version (GD-24). */
    boolean clientApproved(UUID versionId) {
        return Boolean.TRUE.equals(jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM app.brd_client_decision WHERE brd_version_id = :v AND kind = 'APPROVED')")
                .param("v", versionId).query(Boolean.class).single());
    }

    void insertClientDecision(UUID tenantId, UUID projectId, UUID requirementId, UUID versionId, String kind,
                              String itemKey, String note, UUID userId) {
        jdbc.sql("""
                INSERT INTO app.brd_client_decision (tenant_id, project_id, requirement_id, brd_version_id, kind, item_key,
                                                     note, decided_by)
                VALUES (:t, :p, :r, :v, :k, :i, :n, :u)
                """).param("t", tenantId).param("p", projectId).param("r", requirementId).param("v", versionId)
                .param("k", kind).param("i", itemKey).param("n", note).param("u", userId).update();
    }

    /** Flagged items of a version that neither the author (GD-24) nor a Garuda reviewer has accepted. */
    List<String> unacceptedFlags(UUID versionId) {
        return jdbc.sql("""
                SELECT i.item_key FROM app.brd_item i WHERE i.brd_version_id = :v AND i.support_state = 'FLAGGED'
                  AND NOT EXISTS (SELECT 1 FROM app.brd_flag_ack a WHERE a.brd_version_id = i.brd_version_id
                                  AND a.item_key = i.item_key)
                  AND NOT EXISTS (SELECT 1 FROM app.brd_client_decision d WHERE d.brd_version_id = i.brd_version_id
                                  AND d.kind = 'FLAG_ACCEPTED' AND d.item_key = i.item_key)
                ORDER BY i.ordinal
                """).param("v", versionId).query(String.class).list();
    }

    int clientRevisionRequests(UUID requirementId) {
        return jdbc.sql("SELECT count(*) FROM app.brd_client_decision WHERE requirement_id = :r AND kind = 'CHANGES_REQUESTED'")
                .param("r", requirementId).query(Integer.class).single();
    }

    UUID projectOf(UUID requirementId) {
        return jdbc.sql("SELECT project_id FROM app.requirement WHERE id = :r").param("r", requirementId)
                .query(UUID.class).single();
    }

    boolean isBrdApprover(UUID projectId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT app.is_brd_approver(:p)").param("p", projectId)
                .query(Boolean.class).single());
    }

    List<UUID> brdApprovers(UUID projectId) {
        return jdbc.sql("""
                SELECT m.user_id FROM app.project_member m JOIN app.app_user u ON u.id = m.user_id
                WHERE m.project_id = :p AND m.role = 'BRD_APPROVER' AND m.removed_at IS NULL AND u.status = 'ACTIVE'
                """).param("p", projectId).query(UUID.class).list();
    }

    /** Organisation, project and requirement text printed on an exported BRD. */
    BrdDocument.Header exportHeader(UUID requirementId) {
        return jdbc.sql("""
                SELECT t.name, p.name, r.key, rv.title, rv.description, rv.business_objective, rv.expected_outcomes,
                       rv.acceptance_criteria, rv.constraints
                FROM app.requirement r
                JOIN app.requirement_revision rv ON rv.requirement_id = r.id AND rv.revision_no = r.current_revision_no
                JOIN app.project p ON p.id = r.project_id
                JOIN app.tenant t ON t.id = r.tenant_id
                WHERE r.id = :r
                """).param("r", requirementId).query((rs, n) -> new BrdDocument.Header(rs.getString(1),
                        rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9))).single();
    }

    String displayName(UUID userId) {
        return jdbc.sql("SELECT display_name FROM app.app_user WHERE id = :u").param("u", userId)
                .query(String.class).optional().orElse("a member");
    }

    record Feasibility(String verdict, String reasonsJson) {
    }

    Feasibility feasibility(UUID versionId) {
        return jdbc.sql("SELECT feasibility, feasibility_reasons::text FROM app.brd_version WHERE id = :v")
                .param("v", versionId).query((rs, n) -> new Feasibility(rs.getString(1), rs.getString(2))).single();
    }

    record RevisionPrice(UUID quoteId, UUID projectId, String currency, java.math.BigDecimal amount) {
    }

    /** The accepted quote's size price, for change requests beyond those included (none without billing). */
    Optional<RevisionPrice> revisionPrice(UUID requirementId) {
        return jdbc.sql("""
                SELECT id, project_id, currency, list_price FROM app.brd_quote
                WHERE requirement_id = :r AND status = 'ACCEPTED' AND waived_at IS NULL
                """).param("r", requirementId).query((rs, n) -> new RevisionPrice(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getBigDecimal(4))).optional();
    }

    void insertRevisionCharge(UUID tenantId, RevisionPrice price, UUID requirementId, int revisionNo, UUID userId) {
        jdbc.sql("""
                INSERT INTO app.brd_revision_charge (tenant_id, project_id, requirement_id, quote_id, revision_no, currency,
                                                     amount, billing_month, requested_by)
                VALUES (:t, :p, :r, :q, :n, :c, :a, date_trunc('month', now() AT TIME ZONE 'UTC')::date, :u)
                """).param("t", tenantId).param("p", price.projectId()).param("r", requirementId)
                .param("q", price.quoteId()).param("n", revisionNo).param("c", price.currency())
                .param("a", price.amount()).param("u", userId).update();
    }

    Optional<String> latestClientChange(UUID requirementId) {
        return jdbc.sql("""
                SELECT text FROM app.client_change_request WHERE requirement_id = :r ORDER BY created_at DESC LIMIT 1
                """).param("r", requirementId).query(String.class).optional();
    }

    boolean validApproval(UUID versionId) {
        return jdbc.sql("""
                SELECT count(*) FROM app.approval WHERE brd_version_id = :v AND decision = 'APPROVED' AND status = 'VALID'
                """).param("v", versionId).query(Integer.class).single() > 0;
    }

    void invalidateApprovals(UUID versionId, String event) {
        jdbc.sql("""
                UPDATE app.approval SET status = 'INVALIDATED', invalidated_by_event = :e, invalidated_at = clock_timestamp()
                WHERE brd_version_id = :v AND status = 'VALID'
                """).param("e", event).param("v", versionId).update();
    }

    /** Cancellation or rejection: versions in review or internally approved are WITHDRAWN, their approvals invalid. */
    int withdrawOpenVersions(UUID requirementId) {
        jdbc.sql("""
                UPDATE app.approval SET status = 'INVALIDATED', invalidated_by_event = 'RequirementCancelled',
                       invalidated_at = clock_timestamp()
                WHERE status = 'VALID' AND brd_version_id IN (SELECT id FROM app.brd_version WHERE requirement_id = :r
                      AND status IN ('IN_REVIEW', 'APPROVED_INTERNAL'))
                """).param("r", requirementId).update();
        return jdbc.sql("""
                UPDATE app.brd_version SET status = 'WITHDRAWN', status_at = clock_timestamp()
                WHERE requirement_id = :r AND status IN ('IN_REVIEW', 'APPROVED_INTERNAL')
                """).param("r", requirementId).update();
    }

    void setVersionStatus(UUID versionId, String status) {
        jdbc.sql("UPDATE app.brd_version SET status = :s, status_at = clock_timestamp() WHERE id = :v")
                .param("s", status).param("v", versionId).update();
    }

    void insertClientChange(UUID tenantId, UUID requirementId, UUID versionId, String text, UUID userId) {
        jdbc.sql("""
                INSERT INTO app.client_change_request (tenant_id, requirement_id, brd_version_id, text, requested_by)
                VALUES (:t, :r, :v, :text, :u)
                """).param("t", tenantId).param("r", requirementId).param("v", versionId).param("text", text)
                .param("u", userId).update();
    }

    UUID requirementOwner(UUID requirementId) {
        return jdbc.sql("SELECT created_by FROM app.requirement WHERE id = :id").param("id", requirementId)
                .query(UUID.class).single();
    }

    void notify(UUID tenantId, UUID recipient, String kind, UUID requirementId) {
        jdbc.sql("""
                INSERT INTO app.notification (tenant_id, recipient_id, kind, requirement_id) VALUES (:t, :u, :k, :r)
                """).param("t", tenantId).param("u", recipient).param("k", kind).param("r", requirementId).update();
    }

    List<NotificationView> notifications(UUID userId) {
        return jdbc.sql("""
                SELECT id, kind, requirement_id, created_at, read_at FROM app.notification WHERE recipient_id = :u
                ORDER BY created_at DESC LIMIT 100
                """).param("u", userId).query((rs, n) -> new NotificationView(rs.getObject(1, UUID.class),
                        rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, OffsetDateTime.class),
                        rs.getObject(5, OffsetDateTime.class))).list();
    }

    boolean markRead(UUID id, UUID userId) {
        return jdbc.sql("""
                UPDATE app.notification SET read_at = clock_timestamp() WHERE id = :id AND recipient_id = :u AND read_at IS NULL
                """).param("id", id).param("u", userId).update() == 1;
    }
}
