package io.github.tony079.sdlc.sourcerepo.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GD-23: keeps the recorded head commit of every environment branch current, so that a requirement analysed at an
 * older commit can show that its branches have moved. Enqueues one refreshing ENV_VERIFY per repository and interval
 * (idempotent per interval); the verification itself runs as an ordinary job.
 */
@Component
@ConditionalOnProperty(prefix = "sdlc.worker", name = "enabled", havingValue = "true")
public class EnvironmentHeadRefresher {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentHeadRefresher.class);

    private final JdbcClient jdbc;
    private final JobQueue jobs;
    private final TransactionTemplate tx;
    private final long intervalSeconds;

    EnvironmentHeadRefresher(JdbcClient jdbc, JobQueue jobs, PlatformTransactionManager transactionManager,
                             @Value("${sdlc.worker.environment-refresh-interval:15m}") java.time.Duration interval) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.tx = new TransactionTemplate(transactionManager);
        this.intervalSeconds = Math.max(60, interval.toSeconds());
    }

    @Scheduled(fixedDelayString = "${sdlc.worker.environment-refresh-interval:15m}", initialDelayString = "45s")
    void scheduled() {
        try {
            refreshAll();
        } catch (RuntimeException e) {
            log.error("Refreshing environment branch heads failed", e);
        }
    }

    /** Returns the number of refresh jobs enqueued. */
    public int refreshAll() {
        long bucket = Instant.now().getEpochSecond() / intervalSeconds;
        List<UUID> tenants = tx.execute(s -> jdbc.sql("SELECT id FROM app.tenant WHERE status = 'ACTIVE'")
                .query(UUID.class).list());
        int enqueued = 0;
        for (UUID tenant : tenants) {
            enqueued += TenantContext.callAs(tenant, () -> tx.execute(s -> {
                int count = 0;
                for (UUID repositoryId : jdbc.sql("""
                        SELECT DISTINCT b.repository_id FROM app.environment_branch b
                        JOIN app.repository r ON r.id = b.repository_id WHERE r.status = 'VERIFIED'
                        """).query(UUID.class).list()) {
                    if (jobs.enqueue(new JobRequest(EnvironmentVerifyHandler.JOB_TYPE,
                            "env-refresh:" + repositoryId + ":" + bucket, RegistrationService.AGGREGATE, repositoryId,
                            null, Map.of("repositoryId", repositoryId.toString(), "refresh", "true"), 200))) {
                        count++;
                    }
                }
                return count;
            }));
        }
        return enqueued;
    }
}
