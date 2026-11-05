package io.github.tony079.sdlc.outbox.api;

import java.util.Map;
import java.util.UUID;

/** An outbox event as delivered to consumers. The payload holds identifiers and codes only. */
public record DomainEvent(UUID id, long seq, UUID tenantId, String aggregateType, UUID aggregateId,
                          String eventType, int version, Map<String, Object> payload, int attempts) {

    public UUID uuid(String attribute) {
        Object value = payload.get(attribute);
        if (value == null) {
            throw new PermanentEventFailure("PAYLOAD_INVALID");
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            throw new PermanentEventFailure("PAYLOAD_INVALID");
        }
    }

    public String text(String attribute) {
        Object value = payload.get(attribute);
        if (value == null) {
            throw new PermanentEventFailure("PAYLOAD_INVALID");
        }
        return value.toString();
    }
}
