package io.github.tony079.sdlc.audit.internal;

import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.common.api.CorrelationId;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcAuditWriter implements AuditWriter {

    private final JdbcClient jdbc;

    JdbcAuditWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String resourceType, UUID resourceId, AuditResult result) {
        insert(action, resourceType, resourceId, result);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditAction action, String resourceType, UUID resourceId, AuditResult result) {
        insert(action, resourceType, resourceId, result);
    }

    private void insert(AuditAction action, String resourceType, UUID resourceId, AuditResult result) {
        UUID actor = TenantContext.currentActor().orElse(null);
        jdbc.sql("""
                INSERT INTO app.audit_event (tenant_id, actor_type, actor_id, action, resource_type, resource_id,
                                             result, correlation_id)
                VALUES (:tenant, :actorType, :actor, :action, :resourceType, :resourceId, :result, :correlation)
                """)
                .param("tenant", TenantContext.requireTenant())
                .param("actorType", actor == null ? "SYSTEM" : "USER")
                .param("actor", actor)
                .param("action", action.name())
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("result", result.name())
                .param("correlation", CorrelationId.current().orElse(null))
                .update();
    }
}
