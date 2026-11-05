package io.github.tony079.sdlc.outbox.internal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.PermanentEventFailure;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.tenancy.api.TenantLookup;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox dispatcher (TDD 11.2, decisions Q-M3-1 and Q-M3-2). Runs only in the worker process (login sdlc_worker).
 *
 * <p>Single-transaction row-lock dispatch: one event is locked with {@code FOR UPDATE SKIP LOCKED}, its consumers
 * run in the same transaction (each under its own savepoint), and the event is marked published, retried or dead
 * before commit. A crash before commit leaves no effect and releases the lock, so the event is delivered again;
 * consumer effects are committed together with their processed_event row, which makes redelivery harmless.
 * There is no claim lease or dispatch epoch: the row lock is held for the whole delivery.
 *
 * <p>Tenants are processed one at a time inside their own transaction context, so forced RLS applies to every
 * read and write. Ordering: an event is taken only if no earlier undelivered event (pending or dead) of the same
 * aggregate exists; other aggregates are unaffected.
 */
@Component
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    public enum Outcome { NONE, PUBLISHED, RETRY_SCHEDULED, DEAD }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final List<EventConsumer> consumers;
    private final TenantLookup tenants;
    private final AuditWriter audit;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    private final TransactionTemplate nested;
    private final int maxAttempts;
    private final Duration backoffBase;
    private final Duration backoffCap;
    private final int batchSize;

    OutboxDispatcher(JdbcClient jdbc, JsonMapper json, List<EventConsumer> consumers, TenantLookup tenants,
                     AuditWriter audit, MeterRegistry meters, PlatformTransactionManager transactionManager,
                     @Value("${sdlc.worker.outbox.max-attempts:10}") int maxAttempts,
                     @Value("${sdlc.worker.outbox.backoff-base:2s}") Duration backoffBase,
                     @Value("${sdlc.worker.outbox.backoff-cap:5m}") Duration backoffCap,
                     @Value("${sdlc.worker.outbox.batch-size:50}") int batchSize) {
        this.jdbc = jdbc;
        this.json = json;
        this.consumers = List.copyOf(consumers);
        this.tenants = tenants;
        this.audit = audit;
        this.meters = meters;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.nested = new TransactionTemplate(transactionManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.maxAttempts = maxAttempts;
        this.backoffBase = backoffBase;
        this.backoffCap = backoffCap;
        this.batchSize = batchSize;
        if (maxAttempts < 1 || batchSize < 1) {
            throw new IllegalArgumentException("sdlc.worker.outbox.max-attempts and batch-size must be positive");
        }
    }

    /** One pass over every active tenant, at most one batch per tenant. Returns the number of events handled. */
    public int dispatchAll() {
        List<UUID> active = tx.execute(status -> tenants.activeTenantIds());
        int handled = 0;
        for (UUID tenant : active) {
            for (int i = 0; i < batchSize; i++) {
                if (dispatchNext(tenant) == Outcome.NONE) {
                    break;
                }
                handled++;
            }
        }
        return handled;
    }

    /** Delivers the next deliverable event of one tenant, in its own transaction. */
    public Outcome dispatchNext(UUID tenant) {
        return TenantContext.callAs(tenant, () -> tx.execute(this::dispatchOne));
    }

    private record Locked(UUID id, long seq, UUID tenantId, String aggregateType, UUID aggregateId, String eventType,
                          String payload, int attempts) {
    }

    private Outcome dispatchOne(TransactionStatus status) {
        Locked row = jdbc.sql("""
                SELECT e.id, e.seq, e.tenant_id, e.aggregate_type, e.aggregate_id, e.event_type, e.payload::text AS payload,
                       e.attempts
                FROM app.outbox_event e
                WHERE e.published_at IS NULL AND e.dead_at IS NULL AND e.next_attempt_at <= clock_timestamp()
                  AND NOT EXISTS (SELECT 1 FROM app.outbox_event p
                                  WHERE p.tenant_id = e.tenant_id AND p.aggregate_id = e.aggregate_id
                                    AND p.seq < e.seq AND p.published_at IS NULL)
                ORDER BY e.seq
                LIMIT 1
                FOR UPDATE OF e SKIP LOCKED
                """)
                .query((rs, n) -> new Locked(rs.getObject("id", UUID.class), rs.getLong("seq"),
                        rs.getObject("tenant_id", UUID.class), rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                        rs.getInt("attempts")))
                .optional().orElse(null);
        if (row == null) {
            return Outcome.NONE;
        }

        String permanent = null;
        String retryable = null;
        DomainEvent event = null;
        List<EventConsumer> interested = consumers.stream().filter(c -> c.accepts(row.eventType())).toList();
        if (!interested.isEmpty()) {
            Map<String, Object> payload = json.readValue(row.payload(), MAP);
            int version = payload.get("v") instanceof Number n ? n.intValue() : -1;
            if (!EventTypes.isSupported(row.eventType(), version)) {
                permanent = "UNSUPPORTED_EVENT_VERSION";
            } else {
                event = new DomainEvent(row.id(), row.seq(), row.tenantId(), row.aggregateType(), row.aggregateId(),
                        row.eventType(), version, Map.copyOf(payload), row.attempts());
            }
        }
        if (event != null) {
            for (EventConsumer consumer : interested) {
                try {
                    deliver(consumer, event);
                } catch (PermanentEventFailure e) {
                    permanent = e.code();
                    log.warn("Outbox consumer {} rejected event {} ({}) permanently: {}", consumer.name(), row.id(),
                            row.eventType(), e.code());
                } catch (RuntimeException e) {
                    retryable = errorCode(e);
                    log.warn("Outbox consumer {} failed on event {} ({}): {}", consumer.name(), row.id(),
                            row.eventType(), retryable, e);
                }
            }
        }

        if (permanent == null && retryable == null) {
            jdbc.sql("UPDATE app.outbox_event SET published_at = clock_timestamp() WHERE id = :id")
                    .param("id", row.id()).update();
            meters.counter("sdlc.outbox.published", "event_type", row.eventType()).increment();
            return Outcome.PUBLISHED;
        }
        int attempts = row.attempts() + 1;
        String code = permanent != null ? permanent : retryable;
        if (permanent != null || attempts >= maxAttempts) {
            jdbc.sql("""
                    UPDATE app.outbox_event SET attempts = :attempts, last_error_code = :code, dead_at = clock_timestamp()
                    WHERE id = :id
                    """)
                    .param("attempts", attempts).param("code", code).param("id", row.id()).update();
            audit.record(AuditAction.OUTBOX_EVENT_DEAD, "OUTBOX_EVENT", row.id(), AuditResult.FAILED);
            meters.counter("sdlc.outbox.dead", "event_type", row.eventType()).increment();
            // Operational alert (decision Q-M3-6): dead events are recorded and alerted, never skipped. Later events
            // of the same aggregate stay blocked; other aggregates continue. No recovery API until O-06/O-13.
            log.error("ALERT outbox event dead: event={} type={} aggregate={} tenant={} attempts={} code={}",
                    row.id(), row.eventType(), row.aggregateId(), row.tenantId(), attempts, code);
            return Outcome.DEAD;
        }
        jdbc.sql("""
                UPDATE app.outbox_event
                SET attempts = :attempts, last_error_code = :code,
                    next_attempt_at = clock_timestamp() + make_interval(secs => :delay)
                WHERE id = :id
                """)
                .param("attempts", attempts).param("code", code).param("delay", backoffSeconds(attempts))
                .param("id", row.id()).update();
        meters.counter("sdlc.outbox.retried", "event_type", row.eventType()).increment();
        return Outcome.RETRY_SCHEDULED;
    }

    /**
     * Runs one consumer under a savepoint. Its effects and its processed_event row are kept together: on failure
     * both are rolled back to the savepoint (and the transaction stays usable for the other consumers).
     */
    private void deliver(EventConsumer consumer, DomainEvent event) {
        nested.executeWithoutResult(status -> {
            int claimed = jdbc.sql("""
                    INSERT INTO app.processed_event (tenant_id, event_id, consumer) VALUES (:tenant, :event, :consumer)
                    ON CONFLICT (event_id, consumer) DO NOTHING
                    """)
                    .param("tenant", event.tenantId()).param("event", event.id()).param("consumer", consumer.name())
                    .update();
            if (claimed == 1) {
                consumer.handle(event);
            }
        });
    }

    /** Exponential backoff with jitter: min(cap, base * 2^(n-1)) * [0.5, 1.5). */
    double backoffSeconds(int attempts) {
        double base = backoffBase.toMillis() / 1000.0;
        double raw = Math.min(backoffCap.toMillis() / 1000.0, base * Math.pow(2, Math.min(attempts - 1, 30)));
        return raw * ThreadLocalRandom.current().nextDouble(0.5, 1.5);
    }

    /** Content-free error code: never an exception message. */
    static String errorCode(RuntimeException e) {
        if (e instanceof ApiException api) {
            return api.code();
        }
        if (e instanceof DataAccessException) {
            return "DATABASE_ERROR";
        }
        return "CONSUMER_FAILED";
    }
}
