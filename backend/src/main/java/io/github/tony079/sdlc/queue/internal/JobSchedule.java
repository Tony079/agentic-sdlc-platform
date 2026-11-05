package io.github.tony079.sdlc.queue.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.tenancy.api.TenantLookup;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Worker loops for the job queue, plus operational visibility (queue backlog, expired leases, dead jobs) as
 * Micrometer gauges and a periodic structured log line. Also maintains a liveness file for the container health
 * check. Enabled only in the worker process with scheduling on.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "sdlc.worker", name = {"enabled", "scheduling"}, havingValue = "true")
class JobSchedule {

    private static final Logger log = LoggerFactory.getLogger(JobSchedule.class);

    private final JobWorker worker;
    private final TenantLookup tenants;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Path livenessFile;
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong leased = new AtomicLong();
    private final AtomicLong expiredLeases = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong oldestQueuedSeconds = new AtomicLong();

    JobSchedule(JobWorker worker, TenantLookup tenants, JdbcClient jdbc, PlatformTransactionManager transactionManager,
                MeterRegistry meters, @Value("${sdlc.worker.liveness-file:/tmp/sdlc-worker-alive}") Path livenessFile) {
        this.worker = worker;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.livenessFile = livenessFile;
        meters.gauge("sdlc.jobs.queued", queued);
        meters.gauge("sdlc.jobs.leased", leased);
        meters.gauge("sdlc.jobs.lease.expired.current", expiredLeases);
        meters.gauge("sdlc.jobs.dead.total", dead);
        meters.gauge("sdlc.jobs.oldest.queued.seconds", oldestQueuedSeconds);
    }

    @Scheduled(fixedDelayString = "${sdlc.worker.poll-interval:1s}")
    void poll() {
        try {
            worker.pollAsync();
        } catch (RuntimeException e) {
            log.error("Job poll failed", e);
        }
        touchLiveness();
    }

    @Scheduled(fixedDelayString = "${sdlc.worker.snapshot-interval:60s}", initialDelayString = "5s")
    void snapshot() {
        long q = 0;
        long l = 0;
        long x = 0;
        long d = 0;
        long oldest = 0;
        for (UUID tenant : tx.execute(status -> tenants.activeTenantIds())) {
            long[] row = TenantContext.callAs(tenant, () -> tx.execute(status -> jdbc.sql("""
                    SELECT count(*) FILTER (WHERE status = 'QUEUED'),
                           count(*) FILTER (WHERE status = 'LEASED'),
                           count(*) FILTER (WHERE status = 'LEASED' AND lease_expires_at <= clock_timestamp()),
                           count(*) FILTER (WHERE status = 'DEAD'),
                           coalesce(extract(epoch FROM clock_timestamp() - min(available_at) FILTER (WHERE status = 'QUEUED')), 0)
                    FROM app.job
                    """).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                    (long) rs.getDouble(5)}).single()));
            q += row[0];
            l += row[1];
            x += row[2];
            d += row[3];
            oldest = Math.max(oldest, row[4]);
        }
        queued.set(q);
        leased.set(l);
        expiredLeases.set(x);
        dead.set(d);
        oldestQueuedSeconds.set(oldest);
        log.info("ops.snapshot jobs queued={} leased={} expiredLeases={} dead={} oldestQueuedSeconds={} handledTypes={}",
                q, l, x, d, oldest, worker.handledTypes());
        if (x > 0) {
            log.warn("ALERT expired job leases waiting for recovery: {}", x);
        }
    }

    private void touchLiveness() {
        try {
            if (!Files.exists(livenessFile)) {
                Files.createFile(livenessFile);
            }
            Files.setLastModifiedTime(livenessFile, FileTime.from(Instant.now()));
        } catch (IOException e) {
            log.warn("Could not update liveness file {}", livenessFile);
        }
    }
}
