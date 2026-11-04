package io.github.tony079.sdlc.queue.internal;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.queue.api.LeaseLostException;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.tenancy.api.TenantLookup;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job worker (TDD 10). Runs only in the worker process. For each active tenant, inside that tenant's
 * transaction context (decision Q-M3-2), it claims jobs of the types it has handlers for, runs each handler's
 * {@code execute} outside a transaction while heartbeating the lease, and then commits the result in a fenced
 * transaction. Lease loss, crashes and expiry are recovered by the next claim (TDD 10.7).
 */
@Component
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JdbcJobStore store;
    private final TenantLookup tenants;
    private final Map<String, JobHandler<?>> handlers;
    private final TransactionTemplate tx;
    private final String workerId;
    private final int concurrency;
    private final Semaphore slots;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeats;

    JobWorker(JdbcJobStore store, TenantLookup tenants, List<JobHandler<?>> handlers,
              PlatformTransactionManager transactionManager,
              @Value("${sdlc.worker.jobs.concurrency:2}") int concurrency) {
        this.store = store;
        this.tenants = tenants;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(JobHandler::jobType, Function.identity()));
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.workerId = newWorkerId();
        this.concurrency = concurrency;
        this.slots = new Semaphore(concurrency);
        this.executor = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().name("sdlc-job-", 0).factory());
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("sdlc-job-heartbeat").daemon().factory());
        log.info("Job worker {} started for job types {}", workerId, this.handlers.keySet());
    }

    public String workerId() {
        return workerId;
    }

    public List<String> handledTypes() {
        return List.copyOf(handlers.keySet());
    }

    /** Scheduled loop: claims as many jobs as there are free slots and runs them on the worker pool. */
    public void pollAsync() {
        for (UUID tenant : activeTenants()) {
            int free = slots.availablePermits();
            if (free == 0) {
                return;
            }
            for (JdbcJobStore.Claim claim : claim(tenant, free)) {
                slots.acquireUninterruptibly();
                executor.execute(() -> {
                    try {
                        process(claim);
                    } finally {
                        slots.release();
                    }
                });
            }
        }
    }

    /** Synchronous pass (tests, diagnostics): claims and runs up to {@code perTenant} jobs per tenant. */
    public int runOnce(int perTenant) {
        int processed = 0;
        for (UUID tenant : activeTenants()) {
            for (JdbcJobStore.Claim claim : claim(tenant, perTenant)) {
                process(claim);
                processed++;
            }
        }
        return processed;
    }

    /** Claims jobs of one tenant in a short transaction of its own. */
    public List<JdbcJobStore.Claim> claim(UUID tenant, int limit) {
        if (handlers.isEmpty()) {
            return List.of();
        }
        return TenantContext.callAs(tenant,
                () -> tx.execute(status -> store.claim(workerId, List.copyOf(handlers.keySet()), limit)));
    }

    /** Runs one claimed job: execute under heartbeats, then the fenced commit (or the fenced failure record). */
    public void process(JdbcJobStore.Claim claim) {
        ClaimedJob job = claim.job();
        @SuppressWarnings("unchecked")
        JobHandler<Object> handler = (JobHandler<Object>) handlers.get(job.jobType());
        TenantContext.callAs(job.tenantId(), () -> {
            long period = Math.max(1, claim.leaseSeconds() / 3);
            ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> beat(job), period, period, TimeUnit.SECONDS);
            Object result;
            try {
                result = handler.execute(job);
            } catch (JobFailure failure) {
                heartbeat.cancel(false);
                recordFailure(job, failure);
                return null;
            } catch (RuntimeException e) {
                heartbeat.cancel(false);
                log.warn("Job {} ({}) failed unexpectedly", job.id(), job.jobType(), e);
                recordFailure(job, JobFailure.retryable("HANDLER_ERROR", e));
                return null;
            } finally {
                heartbeat.cancel(false);
            }
            try {
                tx.executeWithoutResult(status -> {
                    store.assertLease(job);
                    handler.commit(job, result);
                    store.finishSucceeded(job);
                });
            } catch (LeaseLostException e) {
                log.warn("Job {} ({}) result discarded: {}", job.id(), job.jobType(), e.code());
            } catch (RuntimeException e) {
                log.warn("Job {} ({}) commit failed", job.id(), job.jobType(), e);
                recordFailure(job, JobFailure.retryable("COMMIT_FAILED", e));
            }
            return null;
        });
    }

    private void recordFailure(ClaimedJob job, JobFailure failure) {
        try {
            tx.executeWithoutResult(status -> {
                store.assertLease(job);
                store.finishFailure(job, failure);
            });
        } catch (LeaseLostException e) {
            // Another epoch owns the job (or it was cancelled): this worker's failure is not recorded.
            log.warn("Job {} ({}) failure not recorded: {}", job.id(), job.jobType(), e.code());
        }
    }

    private void beat(ClaimedJob job) {
        try {
            Boolean ok = TenantContext.callAs(job.tenantId(), () -> tx.execute(status -> store.heartbeat(job)));
            if (!Boolean.TRUE.equals(ok)) {
                log.warn("Job {} ({}) lease could not be extended; the result will be rejected", job.id(), job.jobType());
            }
        } catch (RuntimeException e) {
            log.warn("Job {} heartbeat failed", job.id(), e);
        }
    }

    private List<UUID> activeTenants() {
        return new ArrayList<>(tx.execute(status -> tenants.activeTenantIds()));
    }

    int concurrency() {
        return concurrency;
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        heartbeats.shutdownNow();
    }

    private static String newWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9._-]", "-");
        } catch (Exception e) {
            host = "worker";
        }
        if (host.length() > 60) {
            host = host.substring(0, 60);
        }
        return host + ":" + ManagementFactory.getRuntimeMXBean().getPid() + ":"
                + UUID.randomUUID().toString().substring(0, 8);
    }
}
