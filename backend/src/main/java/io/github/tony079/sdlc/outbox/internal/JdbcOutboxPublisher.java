package io.github.tony079.sdlc.outbox.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcOutboxPublisher implements OutboxPublisher {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcOutboxPublisher(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> attributes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", EventTypes.currentVersion(eventType));
        payload.putAll(attributes);
        // Only the five producer columns: the API login has no other privilege on this table.
        jdbc.sql("""
                INSERT INTO app.outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (:tenant, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))
                """)
                .param("tenant", TenantContext.requireTenant())
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }
}
