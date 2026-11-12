package io.github.tony079.sdlc.audit.internal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.tony079.sdlc.audit.api.AuditTrail;

/** Reads audit rows of one requirement and its dependent resources, under row-level security. */
@Component
class JdbcAuditTrail implements AuditTrail {

    private final JdbcClient jdbc;

    JdbcAuditTrail(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<AuditEntry> forRequirement(UUID requirementId, UUID viewer) {
        List<AuditEntry> newestFirst = jdbc.sql("""
                SELECT a.occurred_at, a.actor_type, a.actor_id, a.action, a.resource_type, a.result
                FROM app.audit_event a
                WHERE a.resource_id = :r
                   OR a.resource_id IN (SELECT d.id FROM app.document d WHERE d.requirement_id = :r)
                   OR a.resource_id IN (SELECT v.id FROM app.document_version v
                                        JOIN app.document d ON d.id = v.document_id WHERE d.requirement_id = :r)
                   OR a.resource_id IN (SELECT b.id FROM app.brd_version b WHERE b.requirement_id = :r)
                   OR a.resource_id IN (SELECT c.id FROM app.clarification c WHERE c.requirement_id = :r)
                ORDER BY a.occurred_at DESC, a.id
                LIMIT :limit
                """).param("r", requirementId).param("limit", MAX_ENTRIES)
                .query((rs, n) -> {
                    UUID actor = rs.getObject(3, UUID.class);
                    String type = rs.getString(2);
                    return new AuditEntry(rs.getObject(1, OffsetDateTime.class),
                            actor != null && actor.equals(viewer) ? "YOU" : type, rs.getString(4), rs.getString(5),
                            rs.getString(6));
                }).list();
        List<AuditEntry> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        return oldestFirst;
    }
}
