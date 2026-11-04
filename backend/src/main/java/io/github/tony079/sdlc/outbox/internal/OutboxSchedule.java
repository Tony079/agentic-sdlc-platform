package io.github.tony079.sdlc.outbox.internal;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.tenancy.api.TenantLookup;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox dispatch loop and operational visibility (TDD 11.8): pending events, dead events and the age of the oldest
 * pending event (outbox lag), as Micrometer gauges and a periodic structured log line. Dead events raise an alert
 * line on every snapshot until they are resolved by an owner-approved procedure (decision Q-M3-6).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sdlc.worker", name = {"enabled", "scheduling"}, havingValue = "true")
class OutboxSchedule {

    private static final Logger log = LoggerFactory.getLogger(OutboxSchedule.class);

    private final OutboxDispatcher dispatcher;
    private final TenantLookup tenants;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();

    OutboxSchedule(OutboxDispatcher dispatcher, TenantLookup tenants, JdbcClient jdbc,
                   PlatformTransactionManager transactionManager, MeterRegistry meters) {
        this.dispatcher = dispatcher;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        meters.gauge("sdlc.outbox.pending", pending);
        meters.gauge("sdlc.outbox.dead.total", dead);
        meters.gauge("sdlc.outbox.oldest.pending.seconds", oldestPendingSeconds);
    }

    @Scheduled(fixedDelayString = "${sdlc.worker.poll-interval:1s}")
    void dispatch() {
        try {
            dispatcher.dispatchAll();
        } catch (RuntimeException e) {
            log.error("Outbox dispatch pass failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${sdlc.worker.snapshot-interval:60s}", initialDelayString = "5s")
    void snapshot() {
        long p = 0;
        long d = 0;
        long oldest = 0;
        for (UUID tenant : tx.execute(status -> tenants.activeTenantIds())) {
            long[] row = TenantContext.callAs(tenant, () -> tx.execute(status -> jdbc.sql("""
                    SELECT count(*) FILTER (WHERE published_at IS NULL AND dead_at IS NULL),
                           count(*) FILTER (WHERE dead_at IS NOT NULL),
                           coalesce(extract(epoch FROM clock_timestamp()
                                    - min(created_at) FILTER (WHERE published_at IS NULL AND dead_at IS NULL)), 0)
                    FROM app.outbox_event
                    """).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), (long) rs.getDouble(3)}).single()));
            p += row[0];
            d += row[1];
            oldest = Math.max(oldest, row[2]);
        }
        pending.set(p);
        dead.set(d);
        oldestPendingSeconds.set(oldest);
        log.info("ops.snapshot outbox pending={} dead={} oldestPendingSeconds={}", p, d, oldest);
        if (d > 0) {
            log.error("ALERT outbox has {} dead event(s); later events of those aggregates are blocked", d);
        }
    }
}
