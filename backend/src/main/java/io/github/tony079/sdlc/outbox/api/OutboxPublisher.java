package io.github.tony079.sdlc.outbox.api;

import java.util.Map;
import java.util.UUID;

/**
 * Producer side of the transactional outbox (TDD 11.1). The event is inserted in the caller's transaction, so it
 * exists exactly when the business change that caused it commits; a rollback leaves no event. Requires an active
 * transaction with a tenant context.
 */
public interface OutboxPublisher {

    /**
     * @param attributes identifiers and codes only, never business content; the schema version {@code v} is
     *                   added from {@link EventTypes#currentVersion(String)}
     */
    void publish(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> attributes);
}
