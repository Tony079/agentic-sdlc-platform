package io.github.tony079.sdlc.document.internal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.document.api.DocumentProcessing;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JdbcDocumentProcessing implements DocumentProcessing {

    private static final Set<String> OUTCOMES = Set.of("PROCESSED", "UNSUPPORTED_CONTENT", "EXTRACTION_FAILED_FINAL");

    private final JdbcClient jdbc;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;

    JdbcDocumentProcessing(JdbcClient jdbc, OutboxPublisher outbox, AuditWriter audit) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Override
    public Optional<Input> input(UUID versionId) {
        return jdbc.sql("""
                SELECT id, document_id, requirement_id, state, claimed_type, storage_key, sha256, size_bytes
                FROM app.document_version WHERE id = :id
                """).param("id", versionId)
                .query((rs, n) -> new Input(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getLong(8)))
                .optional();
    }

    @Override
    public Optional<UUID> currentVersion(UUID documentId) {
        return jdbc.sql("""
                SELECT id FROM app.document_version WHERE document_id = :document ORDER BY version_no DESC LIMIT 1
                """).param("document", documentId).query(UUID.class).optional();
    }

    @Override
    public boolean recordOutcome(UUID versionId, String outcome, String reasonCode) {
        if (!OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("Not a processing outcome: " + outcome);
        }
        Input input = jdbc.sql("""
                SELECT id, document_id, requirement_id, state, claimed_type, storage_key, sha256, size_bytes
                FROM app.document_version WHERE id = :id FOR UPDATE
                """).param("id", versionId)
                .query((rs, n) -> new Input(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getLong(8)))
                .optional().orElse(null);
        if (input == null || !"READY_FOR_EXTRACTION".equals(input.state())) {
            return false;
        }
        jdbc.sql("""
                UPDATE app.document_version SET state = :state, reason_code = :reason, updated_at = clock_timestamp()
                WHERE id = :id
                """).param("state", outcome).param("reason", reasonCode).param("id", versionId).update();
        boolean processed = "PROCESSED".equals(outcome);
        outbox.publish("DOCUMENT", input.documentId(), processed ? EventTypes.DOCUMENT_PROCESSED : EventTypes.DOCUMENT_FAILED,
                Map.of("documentId", input.documentId().toString(), "versionId", versionId.toString(),
                        "requirementId", input.requirementId().toString(), "state", outcome));
        audit.record(processed ? AuditAction.DOCUMENT_PROCESSED : AuditAction.DOCUMENT_NOT_ANALYSABLE, "DOCUMENT",
                input.documentId(), processed ? AuditResult.SUCCESS : AuditResult.FAILED);
        return true;
    }
}
