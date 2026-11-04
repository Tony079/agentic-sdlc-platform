package io.github.tony079.sdlc.document.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.tenancy.api.TenantLookup;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Document pipeline visibility (TDD 15.3, 24.4): files waiting in QUARANTINED for more than five minutes (scanner
 * outage, AC-EXT-22) raise an alert; rejected files are counted. Also removes abandoned job workspaces older than
 * 24 hours (TDD 15.7, AC-EXT-39). Worker only.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sdlc.worker", name = {"enabled", "scheduling"}, havingValue = "true")
class DocumentMonitor {

    private static final Logger log = LoggerFactory.getLogger(DocumentMonitor.class);

    private final TenantLookup tenants;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Path workspace;
    private final AtomicLong staleQuarantine = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    DocumentMonitor(TenantLookup tenants, JdbcClient jdbc, PlatformTransactionManager transactionManager,
                    MeterRegistry meters, @Value("${sdlc.worker.workspace:${java.io.tmpdir}/sdlc-work}") Path workspace) {
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.workspace = workspace;
        meters.gauge("sdlc.documents.quarantined.stale", staleQuarantine);
        meters.gauge("sdlc.documents.rejected.total", rejected);
    }

    @Scheduled(fixedDelayString = "${sdlc.worker.snapshot-interval:60s}", initialDelayString = "7s")
    void snapshot() {
        long stale = 0;
        long rejectedCount = 0;
        for (UUID tenant : tx.execute(status -> tenants.activeTenantIds())) {
            long[] row = TenantContext.callAs(tenant, () -> tx.execute(status -> jdbc.sql("""
                    SELECT count(*) FILTER (WHERE state = 'QUARANTINED' AND created_at < clock_timestamp() - interval '5 minutes'),
                           count(*) FILTER (WHERE state IN ('REJECTED_INVALID', 'REJECTED_MALWARE'))
                    FROM app.document_version
                    """).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single()));
            stale += row[0];
            rejectedCount += row[1];
        }
        staleQuarantine.set(stale);
        rejected.set(rejectedCount);
        log.info("ops.snapshot documents staleQuarantined={} rejected={}", stale, rejectedCount);
        if (stale > 0) {
            log.error("ALERT {} document(s) quarantined for more than 5 minutes: scanner unavailable or backlog", stale);
        }
        sweepWorkspaces();
    }

    private void sweepWorkspaces() {
        if (!Files.isDirectory(workspace)) {
            return;
        }
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        try (Stream<Path> jobs = Files.list(workspace)) {
            jobs.filter(p -> {
                try {
                    return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                } catch (IOException e) {
                    return false;
                }
            }).forEach(DocumentScanHandler::deleteRecursively);
        } catch (IOException e) {
            log.warn("Workspace sweep failed");
        }
    }
}
