package io.github.tony079.sdlc.requirement.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency-Key handling for creating operations (TDD 20.1), in the creating transaction.
 *
 * <p>{@link #claim} inserts the key with ON CONFLICT DO NOTHING. A concurrent claimer of the same key blocks until
 * the first transaction ends: if it committed, the claim returns false and {@link #find} (a separate statement,
 * hence a fresh snapshot that sees the committed row) returns the winner; if it rolled back, the claim succeeds.
 * Reading the winner inside the claiming statement would use the stale snapshot and see nothing; that was found
 * and fixed during pre-implementation verification (V-M2-5).
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class IdempotencyStore {

    private final JdbcClient jdbc;

    IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Entry(String requestHash, UUID resourceId) {
    }

    /** True if this transaction now owns the key. */
    boolean claim(UUID tenantId, UUID userId, String operation, UUID key, String requestHash, UUID resourceId) {
        return jdbc.sql("""
                INSERT INTO app.idempotency_record (tenant_id, user_id, operation, idem_key, request_hash, resource_id)
                VALUES (:tenant, :user, :operation, :key, :hash, :resource)
                ON CONFLICT DO NOTHING
                """)
                .param("tenant", tenantId).param("user", userId).param("operation", operation).param("key", key)
                .param("hash", requestHash).param("resource", resourceId)
                .update() == 1;
    }

    Optional<Entry> find(UUID userId, String operation, UUID key) {
        return jdbc.sql("""
                SELECT request_hash, resource_id FROM app.idempotency_record
                WHERE user_id = :user AND operation = :operation AND idem_key = :key
                """)
                .param("user", userId).param("operation", operation).param("key", key)
                .query((rs, n) -> new Entry(rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)))
                .optional();
    }
}
