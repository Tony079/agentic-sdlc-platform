package io.github.tony079.sdlc.document.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.document.api.DocumentView.Item;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Attachment metadata access; tenant isolation by RLS. Runs in the caller's transaction. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class DocumentRepository {

    /** States in which a version counts toward the per-requirement limits and duplicate detection. */
    static final String LIVE = "state NOT IN ('REJECTED_INVALID', 'REJECTED_MALWARE', 'UPLOAD_FAILED', 'SUPERSEDED', 'DELETED')";
    static final List<String> DOWNLOADABLE = List.of("READY_FOR_EXTRACTION", "EXTRACTING", "PROCESSED",
            "EXTRACTION_FAILED", "EXTRACTION_FAILED_FINAL", "UNSUPPORTED_CONTENT");

    private final JdbcClient jdbc;

    DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Usage(int files, long bytes) {
    }

    /** Live attachments of a requirement (not removed, not rejected). */
    Usage usage(UUID requirementId) {
        return jdbc.sql("""
                SELECT count(*), coalesce(sum(v.size_bytes), 0)
                FROM app.document d
                JOIN LATERAL (SELECT * FROM app.document_version x WHERE x.document_id = d.id
                              ORDER BY x.version_no DESC LIMIT 1) v ON true
                WHERE d.requirement_id = :requirement AND d.removed_at IS NULL AND v.""" + LIVE)
                .param("requirement", requirementId)
                .query((rs, n) -> new Usage(rs.getInt(1), rs.getLong(2))).single();
    }

    Optional<UUID> liveDuplicate(UUID requirementId, String sha256) {
        return jdbc.sql("""
                SELECT v.document_id FROM app.document_version v JOIN app.document d ON d.id = v.document_id
                WHERE v.requirement_id = :requirement AND v.sha256 = :sha AND d.removed_at IS NULL AND v.""" + LIVE)
                .param("requirement", requirementId).param("sha", sha256)
                .query(UUID.class).optional();
    }

    void insertDocument(UUID id, UUID tenant, UUID requirementId, UUID actor) {
        jdbc.sql("""
                INSERT INTO app.document (id, tenant_id, requirement_id, created_by)
                VALUES (:id, :tenant, :requirement, :actor)
                """).param("id", id).param("tenant", tenant).param("requirement", requirementId).param("actor", actor)
                .update();
    }

    void insertVersion(UUID id, UUID tenant, UUID documentId, UUID requirementId, String fileName, String declaredMime,
                       DocumentType type, long size, String sha256, String storageKey, UUID actor) {
        jdbc.sql("""
                INSERT INTO app.document_version (id, tenant_id, document_id, requirement_id, version_no, file_name,
                                                  declared_mime, claimed_type, size_bytes, sha256, storage_key, uploaded_by)
                VALUES (:id, :tenant, :document, :requirement, 1, :fileName, :mime, :type, :size, :sha, :key, :actor)
                """)
                .param("id", id).param("tenant", tenant).param("document", documentId).param("requirement", requirementId)
                .param("fileName", fileName).param("mime", declaredMime).param("type", type.name())
                .param("size", size).param("sha", sha256).param("key", storageKey).param("actor", actor)
                .update();
    }

    List<Item> list(UUID requirementId) {
        return jdbc.sql("""
                SELECT d.id AS document_id, v.id AS version_id, v.version_no, v.file_name, v.claimed_type, v.size_bytes,
                       v.state, v.reason_code, v.uploaded_by, v.created_at
                FROM app.document d
                JOIN LATERAL (SELECT * FROM app.document_version x WHERE x.document_id = d.id
                              ORDER BY x.version_no DESC LIMIT 1) v ON true
                WHERE d.requirement_id = :requirement AND d.removed_at IS NULL
                ORDER BY v.created_at, d.id
                """)
                .param("requirement", requirementId)
                .query((rs, n) -> new Item(rs.getObject("document_id", UUID.class), rs.getObject("version_id", UUID.class),
                        rs.getInt("version_no"), rs.getString("file_name"), rs.getString("claimed_type"),
                        rs.getLong("size_bytes"), rs.getString("state"), rs.getString("reason_code"),
                        rs.getObject("uploaded_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                        DOWNLOADABLE.contains(rs.getString("state"))))
                .list();
    }

    record Current(UUID documentId, UUID versionId, String fileName, String state, String storageKey) {
    }

    /** The current version of a non-removed document of the requirement. */
    Optional<Current> current(UUID requirementId, UUID documentId) {
        return jdbc.sql("""
                SELECT d.id, v.id, v.file_name, v.state, v.storage_key
                FROM app.document d
                JOIN LATERAL (SELECT * FROM app.document_version x WHERE x.document_id = d.id
                              ORDER BY x.version_no DESC LIMIT 1) v ON true
                WHERE d.id = :document AND d.requirement_id = :requirement AND d.removed_at IS NULL
                """)
                .param("document", documentId).param("requirement", requirementId)
                .query((rs, n) -> new Current(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5)))
                .optional();
    }

    boolean remove(UUID documentId, UUID actor) {
        return jdbc.sql("""
                UPDATE app.document SET removed_at = clock_timestamp(), removed_by = :actor
                WHERE id = :document AND removed_at IS NULL
                """).param("document", documentId).param("actor", actor).update() == 1;
    }
}
