package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Clarifications and document decisions (tenant rows, forced RLS). */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class ClarificationRepository {

    record ClarificationRow(UUID id, UUID requirementId, String source, UUID stageExecutionId, UUID brdVersionId,
                            int revisionNo, String commitSha, boolean blocking, String assignedRole, String status,
                            String question, String whyItMatters, String interpretationsJson, String recommendedOption,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    record Message(String kind, String authorType, UUID authorId, String text, OffsetDateTime createdAt) {
    }

    record PendingDecision(UUID requirementId, String requirementKey, UUID documentId, UUID versionId, String fileName,
                           String state, String reasonCode) {
    }

    private final JdbcClient jdbc;

    ClarificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
            SELECT id, requirement_id, source, stage_execution_id, brd_version_id, revision_no, commit_sha, blocking,
                   assigned_role, status, question, why_it_matters, interpretations::text, recommended_option,
                   created_at, updated_at
            FROM app.clarification
            """;

    private static ClarificationRow row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ClarificationRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getInt(6), rs.getString(7),
                rs.getBoolean(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), rs.getString(14), rs.getObject(15, OffsetDateTime.class),
                rs.getObject(16, OffsetDateTime.class));
    }

    UUID insert(UUID tenantId, UUID requirementId, String source, UUID stage, UUID brdVersion, int revisionNo,
                String commitSha, String inputHash, boolean blocking, String role, String question, String why,
                String interpretationsJson, String recommended) {
        return jdbc.sql("""
                INSERT INTO app.clarification (tenant_id, requirement_id, source, stage_execution_id, brd_version_id,
                    revision_no, commit_sha, input_hash, blocking, assigned_role, question, why_it_matters,
                    interpretations, recommended_option)
                VALUES (:t, :r, :source, :stage, :version, :rev, :sha, :hash, :blocking, :role, :q, :why,
                        CAST(:interp AS jsonb), :rec)
                RETURNING id
                """).param("t", tenantId).param("r", requirementId).param("source", source).param("stage", stage)
                .param("version", brdVersion).param("rev", revisionNo).param("sha", commitSha).param("hash", inputHash)
                .param("blocking", blocking).param("role", role).param("q", question).param("why", why)
                .param("interp", interpretationsJson).param("rec", recommended).query(UUID.class).single();
    }

    Optional<ClarificationRow> find(UUID id, boolean lock) {
        return jdbc.sql(SELECT + "WHERE id = :id" + (lock ? " FOR UPDATE" : "")).param("id", id)
                .query(ClarificationRepository::row).optional();
    }

    List<ClarificationRow> forRequirement(UUID requirementId) {
        return jdbc.sql(SELECT + "WHERE requirement_id = :r ORDER BY created_at").param("r", requirementId)
                .query(ClarificationRepository::row).list();
    }

    /** Client inbox: questions for the owner of requirements the user created. */
    List<ClarificationRow> ownerInbox(UUID userId) {
        return jdbc.sql(SELECT + """
                WHERE assigned_role = 'REQUIREMENT_OWNER'
                  AND requirement_id IN (SELECT id FROM app.requirement WHERE created_by = :u)
                ORDER BY status IN ('OPEN', 'NEEDS_INFO') DESC, created_at
                """).param("u", userId).query(ClarificationRepository::row).list();
    }

    /** Staff inbox of the active tenant: every clarification (reviewers see client questions too, read-only). */
    List<ClarificationRow> tenantInbox() {
        return jdbc.sql(SELECT + "ORDER BY status IN ('OPEN', 'NEEDS_INFO', 'ANSWERED') DESC, blocking DESC, created_at")
                .query(ClarificationRepository::row).list();
    }

    List<Message> messages(UUID clarificationId) {
        return jdbc.sql("""
                SELECT kind, author_type, author_id, text, created_at FROM app.clarification_message
                WHERE clarification_id = :c ORDER BY created_at
                """).param("c", clarificationId)
                .query((rs, n) -> new Message(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class))).list();
    }

    void message(UUID tenantId, UUID clarificationId, String kind, String authorType, UUID authorId, String text) {
        jdbc.sql("""
                INSERT INTO app.clarification_message (tenant_id, clarification_id, kind, author_type, author_id, text)
                VALUES (:t, :c, :kind, :type, :author, :text)
                """).param("t", tenantId).param("c", clarificationId).param("kind", kind).param("type", authorType)
                .param("author", authorId).param("text", text).update();
    }

    void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE app.clarification SET status = :s, updated_at = clock_timestamp() WHERE id = :id")
                .param("s", status).param("id", id).update();
    }

    void setRole(UUID id, String role) {
        jdbc.sql("UPDATE app.clarification SET assigned_role = :r, updated_at = clock_timestamp() WHERE id = :id")
                .param("r", role).param("id", id).update();
    }

    int openBlocking(UUID requirementId) {
        return jdbc.sql("""
                SELECT count(*) FROM app.clarification WHERE requirement_id = :r AND blocking
                  AND status IN ('OPEN', 'NEEDS_INFO', 'ANSWERED')
                """).param("r", requirementId).query(Integer.class).single();
    }

    /** Answers (and rejections) of resolved clarifications not yet used as analysis input, for the resumed run. */
    List<java.util.Map<String, String>> answeredInputs(UUID requirementId) {
        return jdbc.sql("""
                SELECT c.id, c.question, c.status, m.kind, m.text FROM app.clarification c
                JOIN LATERAL (SELECT kind, text FROM app.clarification_message x WHERE x.clarification_id = c.id
                              AND x.kind IN ('ANSWER', 'REJECTION') ORDER BY created_at DESC LIMIT 1) m ON true
                WHERE c.requirement_id = :r AND c.status IN ('RESOLVED', 'REJECTED')
                ORDER BY c.created_at
                """).param("r", requirementId).query((rs, n) -> {
                    java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
                    m.put("source", "CLARIFICATION");
                    m.put("clarificationId", rs.getString(1));
                    m.put("question", rs.getString(2));
                    m.put(rs.getString(4).equals("ANSWER") ? "answer" : "rejectedBecause", rs.getString(5));
                    return m;
                }).list();
    }

    int withdrawOpen(UUID requirementId) {
        return jdbc.sql("""
                UPDATE app.clarification SET status = 'WITHDRAWN', updated_at = clock_timestamp()
                WHERE requirement_id = :r AND status IN ('OPEN', 'NEEDS_INFO', 'ANSWERED')
                """).param("r", requirementId).update();
    }

    // --- document decisions -------------------------------------------------------------------------------------

    List<PendingDecision> pendingDecisions() {
        return jdbc.sql("""
                SELECT v.requirement_id, r.key, v.document_id, v.id, v.file_name, v.state, v.reason_code
                FROM app.document_version v JOIN app.requirement r ON r.id = v.requirement_id
                JOIN app.workflow_instance w ON w.requirement_id = r.id
                WHERE v.state IN ('UNSUPPORTED_CONTENT', 'EXTRACTION_FAILED_FINAL', 'UPLOAD_FAILED')
                  AND v.version_no = (SELECT max(o.version_no) FROM app.document_version o WHERE o.document_id = v.document_id)
                  AND NOT EXISTS (SELECT 1 FROM app.document_decision d WHERE d.document_version_id = v.id)
                  AND w.projected_state IN ('AWAITING_DOCUMENT_DECISION', 'AWAITING_DOCUMENTS')
                ORDER BY v.created_at
                """).query((rs, n) -> new PendingDecision(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6),
                        rs.getString(7))).list();
    }

    record VersionInfo(UUID requirementId, UUID documentId, String state, boolean current) {
    }

    Optional<VersionInfo> versionInfo(UUID versionId) {
        return jdbc.sql("""
                SELECT requirement_id, document_id, state,
                       version_no = (SELECT max(o.version_no) FROM app.document_version o WHERE o.document_id = v.document_id)
                FROM app.document_version v WHERE id = :id
                """).param("id", versionId).query((rs, n) -> new VersionInfo(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getBoolean(4))).optional();
    }

    Optional<UUID> currentVersionOf(UUID documentId) {
        return jdbc.sql("""
                SELECT id FROM app.document_version WHERE document_id = :d ORDER BY version_no DESC LIMIT 1
                """).param("d", documentId).query(UUID.class).optional();
    }

    boolean recordDecision(UUID tenantId, UUID requirementId, UUID versionId, String state, String decision,
                           String actorType, UUID actorId, String reason) {
        return jdbc.sql("""
                INSERT INTO app.document_decision (tenant_id, requirement_id, document_version_id, version_state, decision,
                                                   actor_type, actor_id, reason)
                VALUES (:t, :r, :v, :s, :d, :at, :a, :reason) ON CONFLICT DO NOTHING
                """).param("t", tenantId).param("r", requirementId).param("v", versionId).param("s", state)
                .param("d", decision).param("at", actorType).param("a", actorId).param("reason", reason).update() == 1;
    }
}
