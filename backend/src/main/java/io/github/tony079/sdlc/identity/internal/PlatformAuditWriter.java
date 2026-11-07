package io.github.tony079.sdlc.identity.internal;

import java.util.UUID;

import io.github.tony079.sdlc.common.api.CorrelationId;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff and provisioning audit (no tenant), content-free: ids, codes and results only; never passwords, TOTP codes or
 * secrets. Written in its own transaction so that refusals stay on record when the caller's transaction rolls back.
 */
@Component
class PlatformAuditWriter {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    PlatformAuditWriter(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void record(UUID actorStaffId, String action, String targetType, String targetRef, UUID tenantRef, String result) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                INSERT INTO app.platform_audit_event (actor_staff_id, action, target_type, target_ref, tenant_ref, result,
                                                      correlation_id)
                VALUES (:actor, :action, :targetType, :targetRef, :tenant, :result, :correlation)
                """)
                .param("actor", actorStaffId).param("action", action).param("targetType", targetType)
                .param("targetRef", targetRef).param("tenant", tenantRef).param("result", result)
                .param("correlation", CorrelationId.current().orElse(null))
                .update());
    }
}
