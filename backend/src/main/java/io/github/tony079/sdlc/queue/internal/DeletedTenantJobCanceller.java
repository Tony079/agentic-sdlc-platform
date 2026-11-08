package io.github.tony079.sdlc.queue.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O-25 tenant soft deletion: background processing stops safely. The worker already claims jobs and dispatches events
 * of ACTIVE tenants only; this cancels the unfinished jobs of DELETED tenants and raises their lease epoch, so a worker
 * still running one can no longer commit (the same fencing as requirement cancellation). Nothing is deleted.
 */
@Component
@ConditionalOnProperty(prefix = "sdlc.worker", name = "enabled", havingValue = "true")
public class DeletedTenantJobCanceller {

    private static final Logger log = LoggerFactory.getLogger(DeletedTenantJobCanceller.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    DeletedTenantJobCanceller(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${sdlc.worker.deleted-tenant-interval:30s}", initialDelayString = "15s")
    void scheduled() {
        try {
            cancelJobsOfDeletedTenants();
        } catch (RuntimeException e) {
            log.error("Cancelling jobs of deleted tenants failed", e);
        }
    }

    /** Returns the number of jobs cancelled. */
    public int cancelJobsOfDeletedTenants() {
        List<UUID> deleted = tx.execute(s -> jdbc.sql("SELECT id FROM app.tenant WHERE status = 'DELETED'")
                .query(UUID.class).list());
        int cancelled = 0;
        for (UUID tenant : deleted) {
            cancelled += TenantContext.callAs(tenant, () -> tx.execute(s -> {
                jdbc.sql("""
                        UPDATE app.job_attempt a SET finished_at = clock_timestamp(), outcome = 'CANCELLED',
                               error_code = 'TENANT_DELETED'
                        FROM app.job j WHERE a.job_id = j.id AND j.status = 'LEASED' AND a.finished_at IS NULL
                        """).update();
                return jdbc.sql("""
                        UPDATE app.job SET status = 'CANCELLED', lease_epoch = lease_epoch + 1, lease_owner = NULL,
                               leased_at = NULL, lease_expires_at = NULL, finished_at = clock_timestamp(),
                               last_error_code = 'TENANT_DELETED'
                        WHERE status IN ('QUEUED', 'LEASED')
                        """).update();
            }));
        }
        if (cancelled > 0) {
            log.info("Cancelled {} jobs of soft-deleted tenants", cancelled);
        }
        return cancelled;
    }
}
