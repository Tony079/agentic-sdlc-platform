package io.github.tony079.sdlc.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.PermanentEventFailure;
import io.github.tony079.sdlc.outbox.internal.OutboxDispatcher.Outcome;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;
import io.github.tony079.sdlc.tenancy.api.TenantLookup;
import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox dispatcher (decision Q-M3-1), run as sdlc_worker against the real schema, with probe consumers.
 * Each test works in its own fresh tenant so no other test's events interfere. Events use a type that has no
 * production consumer yet (ManualIntakeEntered), so only the probes react to them.
 */
class OutboxDispatcherIT {

    private static final String TYPE = "ManualIntakeEntered";

    private static JdbcTemplate admin;
    private UUID tenant;
    private final Probe first = new Probe("probe-first");
    private final Probe second = new Probe("probe-second");

    /** A consumer that records deliveries and fails on request. */
    static final class Probe implements EventConsumer {
        final String name;
        final List<DomainEvent> delivered = new CopyOnWriteArrayList<>();
        final Set<UUID> failRetryable = ConcurrentHashMap.newKeySet();
        final Set<UUID> failPermanent = ConcurrentHashMap.newKeySet();
        final Set<UUID> crash = ConcurrentHashMap.newKeySet();

        Probe(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean accepts(String eventType) {
            return TYPE.equals(eventType);
        }

        @Override
        public void handle(DomainEvent event) {
            if (crash.contains(event.aggregateId())) {
                throw new AssertionError("simulated process crash before commit");
            }
            if (failPermanent.contains(event.aggregateId())) {
                throw new PermanentEventFailure("PROBE_REJECTED");
            }
            if (failRetryable.contains(event.aggregateId())) {
                throw new IllegalStateException("probe failure with secret text that must not be stored");
            }
            delivered.add(event);
        }
    }

    @BeforeAll
    static void setUp() {
        WorkerContext.get();
        admin = TestDatabase.get().admin();
    }

    @BeforeEach
    void freshTenant() {
        tenant = WorkerContext.newTenant("outbox");
    }

    private OutboxDispatcher dispatcher(int maxAttempts, EventConsumer... consumers) {
        return dispatcher(maxAttempts, Duration.ZERO, consumers);
    }

    private OutboxDispatcher dispatcher(int maxAttempts, Duration backoff, EventConsumer... consumers) {
        return new OutboxDispatcher(WorkerContext.bean(JdbcClient.class), WorkerContext.bean(JsonMapper.class),
                List.of(consumers), WorkerContext.bean(TenantLookup.class), WorkerContext.bean(AuditWriter.class),
                WorkerContext.bean(MeterRegistry.class), WorkerContext.bean(PlatformTransactionManager.class),
                maxAttempts, backoff, backoff, 50);
    }

    private UUID event(UUID aggregate) {
        return event(aggregate, "{\"v\": 1}");
    }

    private UUID event(UUID aggregate, String payload) {
        return admin.queryForObject("""
                INSERT INTO app.outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'REQUIREMENT', ?, ?, CAST(? AS jsonb)) RETURNING id
                """, UUID.class, tenant, aggregate, TYPE, payload);
    }

    private Map<String, Object> row(UUID event) {
        return admin.queryForMap("SELECT published_at, dead_at, attempts, last_error_code FROM app.outbox_event WHERE id = ?",
                event);
    }

    private List<String> processedBy(UUID event) {
        return admin.queryForList("SELECT consumer FROM app.processed_event WHERE event_id = ? ORDER BY consumer",
                String.class, event);
    }

    @Test
    void deliversToEveryConsumerAndPublishesInOneTransaction() {
        UUID id = event(UUID.randomUUID());
        OutboxDispatcher dispatcher = dispatcher(3, first, second);

        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.PUBLISHED);
        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.NONE);
        assertThat(first.delivered).extracting(DomainEvent::id).containsExactly(id);
        assertThat(second.delivered).extracting(DomainEvent::id).containsExactly(id);
        assertThat(row(id).get("published_at")).isNotNull();
        assertThat(processedBy(id)).containsExactly("probe-first", "probe-second");
    }

    /** Savepoint isolation: a consumer that succeeded is not run again when another consumer fails and retries. */
    @Test
    void consumerSuccessIsNotRepeatedWhenAnotherConsumerFails() {
        UUID aggregate = UUID.randomUUID();
        UUID id = event(aggregate);
        second.failRetryable.add(aggregate);
        OutboxDispatcher dispatcher = dispatcher(5, first, second);

        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(processedBy(id)).containsExactly("probe-first");
        assertThat(row(id)).containsEntry("attempts", 1).containsEntry("last_error_code", "CONSUMER_FAILED");
        assertThat(row(id).get("published_at")).isNull();

        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.RETRY_SCHEDULED);
        second.failRetryable.clear();
        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.PUBLISHED);

        assertThat(first.delivered).hasSize(1);
        assertThat(second.delivered).hasSize(1);
        assertThat(processedBy(id)).containsExactly("probe-first", "probe-second");
        // The error code is content-free; the exception message was not stored anywhere.
        assertThat(admin.queryForObject("SELECT count(*) FROM app.outbox_event WHERE payload::text LIKE '%secret%' "
                + "OR last_error_code LIKE '%SECRET%'", Integer.class)).isZero();
    }

    /** Per-aggregate ordering: a pending earlier event blocks later events of the same aggregate only. */
    @Test
    void failingEventBlocksOnlyItsOwnAggregate() {
        UUID blocked = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID blocked1 = event(blocked);
        UUID blocked2 = event(blocked);
        UUID other1 = event(other);
        first.failRetryable.add(blocked);
        // Real backoff: the failed event is not eligible again for a while, so other aggregates proceed.
        OutboxDispatcher dispatcher = dispatcher(10, Duration.ofHours(1), first);

        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.PUBLISHED);
        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.NONE);
        assertThat(first.delivered).extracting(DomainEvent::id).containsExactly(other1);
        assertThat(row(blocked2).get("attempts")).isEqualTo(0);
        assertThat((java.time.OffsetDateTime) admin.queryForObject(
                "SELECT next_attempt_at FROM app.outbox_event WHERE id = ?", java.time.OffsetDateTime.class, blocked1))
                .isAfter(java.time.OffsetDateTime.now().plusMinutes(20));

        first.failRetryable.clear();
        admin.update("UPDATE app.outbox_event SET next_attempt_at = clock_timestamp() WHERE id = ?", blocked1);
        while (dispatcher.dispatchNext(tenant) != Outcome.NONE) {
            // drain
        }
        assertThat(first.delivered).extracting(DomainEvent::id).containsExactly(other1, blocked1, blocked2);
    }

    /** Decision Q-M3-6: a dead event is recorded and audited, blocks its successors and leaves other aggregates alone. */
    @Test
    void retriesUpToTheLimitThenDeadLettersAndAudits() {
        UUID poisoned = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        UUID dead = event(poisoned);
        UUID successor = event(poisoned);
        UUID unrelated = event(healthy);
        first.failRetryable.add(poisoned);
        OutboxDispatcher dispatcher = dispatcher(3, first);

        List<Outcome> outcomes = new ArrayList<>();
        Outcome outcome;
        while ((outcome = dispatcher.dispatchNext(tenant)) != Outcome.NONE) {
            outcomes.add(outcome);
        }
        // No backoff in this test: the poisoned event (lowest seq) is retried until dead, then the unrelated one goes.
        assertThat(outcomes).containsExactly(Outcome.RETRY_SCHEDULED, Outcome.RETRY_SCHEDULED, Outcome.DEAD,
                Outcome.PUBLISHED);
        assertThat(row(dead)).containsEntry("attempts", 3).containsEntry("last_error_code", "CONSUMER_FAILED");
        assertThat(row(dead).get("dead_at")).isNotNull();
        assertThat(row(successor).get("published_at")).isNull();
        assertThat(row(unrelated).get("published_at")).isNotNull();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.audit_event WHERE tenant_id = ? AND action = 'OUTBOX_EVENT_DEAD'
                  AND resource_type = 'OUTBOX_EVENT' AND resource_id = ? AND actor_type = 'SYSTEM' AND result = 'FAILED'
                """, Integer.class, tenant, dead)).isEqualTo(1);

        // Even after the cause is fixed nothing is skipped automatically: the successor stays blocked.
        first.failRetryable.clear();
        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.NONE);
        // A dead event is final, even for the superuser (no recovery path, decision Q-M3-6).
        assertThatThrownBy(() -> admin.update("UPDATE app.outbox_event SET dead_at = NULL WHERE id = ?", dead))
                .hasMessageContaining("is final");
    }

    @Test
    void permanentFailureAndUnsupportedVersionGoDeadAtOnce() {
        UUID rejected = UUID.randomUUID();
        UUID rejectedEvent = event(rejected);
        UUID futureVersion = event(UUID.randomUUID(), "{\"v\": 7}");
        first.failPermanent.add(rejected);
        OutboxDispatcher dispatcher = dispatcher(10, first);

        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.DEAD);
        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.DEAD);
        assertThat(row(rejectedEvent)).containsEntry("attempts", 1).containsEntry("last_error_code", "PROBE_REJECTED");
        assertThat(row(futureVersion)).containsEntry("last_error_code", "UNSUPPORTED_EVENT_VERSION");
        assertThat(first.delivered).isEmpty();
    }

    /** A crash before commit leaves no effect and no processed_event row; the event is delivered again later. */
    @Test
    void crashBeforeCommitRollsBackEverythingAndIsRedelivered() {
        UUID aggregate = UUID.randomUUID();
        UUID id = event(aggregate);
        second.crash.add(aggregate);
        OutboxDispatcher dispatcher = dispatcher(3, first, second);

        assertThatThrownBy(() -> dispatcher.dispatchNext(tenant)).isInstanceOf(AssertionError.class);
        assertThat(row(id)).containsEntry("attempts", 0);
        assertThat(row(id).get("published_at")).isNull();
        assertThat(processedBy(id)).isEmpty();

        second.crash.clear();
        first.delivered.clear();
        assertThat(dispatcher.dispatchNext(tenant)).isEqualTo(Outcome.PUBLISHED);
        assertThat(first.delivered).hasSize(1);
        assertThat(second.delivered).hasSize(1);
    }

    /** Concurrent dispatchers: every event is delivered exactly once and each aggregate's order is kept. */
    @Test
    void concurrentDispatchersDeliverEachEventOnceInAggregateOrder() throws Exception {
        List<UUID> aggregates = new ArrayList<>();
        Map<UUID, List<UUID>> expected = new ConcurrentHashMap<>();
        for (int a = 0; a < 15; a++) {
            UUID aggregate = UUID.randomUUID();
            aggregates.add(aggregate);
            List<UUID> events = new ArrayList<>();
            for (int e = 0; e < 3; e++) {
                events.add(event(aggregate));
            }
            expected.put(aggregate, events);
        }
        OutboxDispatcher dispatcher = dispatcher(3, first);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                futures.add(pool.submit(() -> {
                    int idle = 0;
                    while (idle < 20) {
                        idle = dispatcher.dispatchNext(tenant) == Outcome.NONE ? idle + 1 : 0;
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(first.delivered).hasSize(45);
        assertThat(first.delivered.stream().map(DomainEvent::id).distinct().count()).isEqualTo(45);
        for (UUID aggregate : aggregates) {
            List<UUID> order = first.delivered.stream().filter(e -> e.aggregateId().equals(aggregate))
                    .map(DomainEvent::id).toList();
            assertThat(order).as("order of " + aggregate).containsExactlyElementsOf(expected.get(aggregate));
        }
        assertThat(admin.queryForObject("SELECT count(*) FROM app.outbox_event WHERE tenant_id = ? AND published_at IS NULL",
                Integer.class, tenant)).isZero();
    }
}
