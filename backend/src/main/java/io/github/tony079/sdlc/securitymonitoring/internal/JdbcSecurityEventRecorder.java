package io.github.tony079.sdlc.securitymonitoring.internal;

import io.github.tony079.sdlc.common.api.CorrelationId;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcSecurityEventRecorder implements SecurityEventRecorder {

    private final JdbcClient jdbc;

    JdbcSecurityEventRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(SecurityEventType type, Severity severity, String actorRef, String targetRef) {
        jdbc.sql("""
                INSERT INTO app.platform_security_event (type, severity, tenant_ref, actor_ref, target_ref, correlation_id)
                VALUES (:type, :severity, :tenant, :actor, :target, :correlation)
                """)
                .param("type", type.name())
                .param("severity", severity.name())
                .param("tenant", TenantContext.currentTenant().orElse(null))
                .param("actor", truncate(actorRef))
                .param("target", truncate(targetRef))
                .param("correlation", CorrelationId.current().orElse(null))
                .update();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 128 ? value : value.substring(0, 128);
    }
}
