package io.github.tony079.sdlc.outbox.api;

/**
 * An in-process consumer of outbox events (TDD 11.2). Delivery is at least once; the dispatcher makes each
 * consumer effectively-once by committing the consumer's effects together with its processed_event row, inside
 * the tenant context of the event. Each consumer runs under its own savepoint: one consumer's failure does not
 * undo another consumer's committed effects, and a consumer that already succeeded is not run again on retry.
 *
 * <p>Throw {@link PermanentEventFailure} for an event that can never succeed (it goes dead at once); any other
 * exception is retried with backoff up to the configured limit.
 */
public interface EventConsumer {

    /** Stable consumer name, lower-case with hyphens; part of the idempotency key. Never rename. */
    String name();

    boolean accepts(String eventType);

    void handle(DomainEvent event);
}
