package io.github.tony079.sdlc.requirement.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human-readable requirement keys REQ-000123, per tenant (decision C-03). Runs in the creating transaction: the
 * upsert locks the tenant's counter row until commit, so concurrent creates in one tenant are serialised, and a
 * rollback also undoes the increment, so committed keys have no gaps. The unique (tenant_id, key) constraint is
 * the backstop. Verified under 60 concurrent transactions with rollbacks before implementation (V-M2-1).
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class RequirementKeyGenerator {

    private final JdbcClient jdbc;

    RequirementKeyGenerator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    String next(UUID tenantId) {
        long value = jdbc.sql("""
                INSERT INTO app.requirement_key_counter (tenant_id, last_value) VALUES (:tenant, 1)
                ON CONFLICT (tenant_id) DO UPDATE SET last_value = app.requirement_key_counter.last_value + 1
                RETURNING last_value
                """)
                .param("tenant", tenantId)
                .query(Long.class)
                .single();
        return format(value);
    }

    /** Six digits, zero-padded; widens naturally after REQ-999999. */
    static String format(long value) {
        return String.format("REQ-%06d", value);
    }
}
