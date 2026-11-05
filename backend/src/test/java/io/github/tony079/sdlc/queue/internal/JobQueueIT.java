package io.github.tony079.sdlc.queue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.queue.api.JobRequest;
import io.github.tony079.sdlc.queue.api.LeaseLostException;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.tenancy.api.TenantLookup;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The PostgreSQL job queue (PRD R1-M1, TDD 10; Q-M3-4 rejected the deferral, so the queue is built now), run as
 * sdlc_worker against the real schema. Each test works in a fresh tenant. Covers atomic claiming, leases,
 * heartbeats, fencing epochs, bounded retries with backoff, dead-lettering, expired-lease and crash recovery,
 * cancellation, tenant isolation and the database privilege boundary.
 */
class JobQueueIT {

    private static final String TYPE = "DOCUMENT_SCAN";

    private static JdbcTemplate admin;
    private static JdbcJobStore store;
    private static TransactionTemplate tx;
    private UUID tenant;
    private UUID aggregate;

    @BeforeAll
    static void setUp() {
        WorkerContext.get();
        admin = TestDatabase.get().admin();
        store = WorkerContext.bean(JdbcJobStore.class);
        tx = new TransactionTemplate(WorkerContext.bean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @BeforeEach
    void freshTenant() {
        tenant = WorkerContext.newTenant("jobs");
        aggregate = UUID.randomUUID();
    }

    private <T> T inTenant(UUID tenantId, Supplier<T> action) {
        return TenantContext.callAs(tenantId, () -> tx.execute(status -> action.get()));
    }

    private <T> T inTenant(Supplier<T> action) {
        return inTenant(tenant, action);
    }

    private UUID enqueue(String key) {
        inTenant(() -> store.enqueue(new JobRequest(TYPE, key, "DOCUMENT", aggregate, null, Map.of("k", key), 100)));
        return admin.queryForObject("SELECT id FROM app.job WHERE tenant_id = ? AND idempotency_key = ?", UUID.class,
                tenant, key);
    }

    private List<JdbcJobStore.Claim> claim(String worker, int limit) {
        return inTenant(() -> store.claim(worker, List.of(TYPE), limit));
    }

    private ClaimedJob claimOne(String worker) {
        List<JdbcJobStore.Claim> claims = claim(worker, 1);
        assertThat(claims).hasSize(1);
        return claims.get(0).job();
    }

    private Map<String, Object> job(UUID id) {
        return admin.queryForMap("""
                SELECT status, lease_owner, lease_epoch, claim_count, attempt_count, retryable_failure_count,
                       infra_failure_count, deferral_count, last_error_code, dead_reason, max_attempts,
                       available_at > clock_timestamp() + interval '4 seconds' AS backed_off
                FROM app.job WHERE id = ?
                """, id);
    }

    private void expireLease(UUID id) {
        admin.update("UPDATE app.job SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE id = ?", id);
    }

    private void makeAvailable(UUID id) {
        admin.update("UPDATE app.job SET available_at = clock_timestamp() WHERE id = ?", id);
    }

    private void commitSucceeded(ClaimedJob job) {
        inTenant(() -> {
            store.assertLease(job);
            store.finishSucceeded(job);
            return null;
        });
    }

    private void commitFailure(ClaimedJob job, JobFailure failure) {
        inTenant(() -> {
            store.assertLease(job);
            store.finishFailure(job, failure);
            return null;
        });
    }

    @Test
    void enqueueIsIdempotentAndPolicyComesFromTheJobType() {
        UUID id = enqueue("scan:1");
        assertThat(inTenant(() -> store.enqueue(new JobRequest(TYPE, "scan:1", "DOCUMENT", aggregate, null, Map.of(), 1))))
                .isFalse();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.job WHERE tenant_id = ?", Integer.class, tenant))
                .isEqualTo(1);
        assertThat(job(id)).containsEntry("status", "QUEUED").containsEntry("max_attempts", 3)
                .containsEntry("lease_epoch", 0L);

        // Even a privileged insert cannot raise the limits or pre-set the delivery state: the trigger copies the policy.
        UUID forged = admin.queryForObject("""
                INSERT INTO app.job (tenant_id, job_type, idempotency_key, aggregate_type, aggregate_id, max_attempts,
                                     max_infra_failures, backoff_base_seconds, backoff_cap_seconds, lease_seconds,
                                     max_runtime_seconds, status, lease_epoch, retryable_failure_count)
                VALUES (?, ?, 'forged', 'DOCUMENT', ?, 99, 99, 1, 1, 900, 7200, 'SUCCEEDED', 42, 5) RETURNING id
                """, UUID.class, tenant, TYPE, aggregate);
        assertThat(job(forged)).containsEntry("status", "QUEUED").containsEntry("max_attempts", 3)
                .containsEntry("lease_epoch", 0L).containsEntry("retryable_failure_count", 0);
    }

    @Test
    void concurrentWorkersClaimEveryJobExactlyOnce() throws Exception {
        for (int i = 0; i < 40; i++) {
            enqueue("scan:" + i);
        }
        Set<UUID> claimed = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < 6; w++) {
                String worker = "it-worker-" + w;
                futures.add(pool.submit(() -> {
                    int empty = 0;
                    while (empty < 5) {
                        List<JdbcJobStore.Claim> batch = claim(worker, 3);
                        empty = batch.isEmpty() ? empty + 1 : 0;
                        for (JdbcJobStore.Claim c : batch) {
                            if (!claimed.add(c.job().id())) {
                                duplicates.incrementAndGet();
                            }
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(duplicates).hasValue(0);
        assertThat(claimed).hasSize(40);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.job WHERE tenant_id = ? AND status = 'LEASED' AND claim_count = 1 AND lease_epoch = 1
                """, Integer.class, tenant)).isEqualTo(40);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.job_attempt WHERE tenant_id = ? AND finished_at IS NULL
                """, Integer.class, tenant)).isEqualTo(40);
    }

    @Test
    void aLiveLeaseIsNeverGivenAwayAndHeartbeatsExtendIt() {
        UUID id = enqueue("scan:live");
        ClaimedJob holder = claimOne("worker-a");
        assertThat(claim("worker-b", 5)).isEmpty();
        assertThat(inTenant(() -> store.heartbeat(holder))).isTrue();
        ClaimedJob impostor = new ClaimedJob(holder.id(), tenant, TYPE, "DOCUMENT", aggregate, null, Map.of(), "worker-b",
                holder.leaseEpoch(), 1);
        assertThat(inTenant(() -> store.heartbeat(impostor))).isFalse();
        assertThatThrownBy(() -> commitSucceeded(impostor)).isInstanceOf(LeaseLostException.class)
                .hasMessage("LEASE_LOST");
        commitSucceeded(holder);
        assertThat(job(id)).containsEntry("status", "SUCCEEDED").containsEntry("lease_owner", null);
    }

    /** An expired worker cannot commit, even before anyone reclaims the job (TDD 10.5, L2). */
    @Test
    void expiredWorkerCannotCommitBeforeReclaim() {
        UUID id = enqueue("scan:expired");
        ClaimedJob job = claimOne("worker-a");
        expireLease(id);
        assertThatThrownBy(() -> commitSucceeded(job)).isInstanceOf(LeaseLostException.class).hasMessage("LEASE_EXPIRED");
        assertThat(inTenant(() -> store.heartbeat(job))).isFalse();
        assertThat(job(id)).containsEntry("status", "LEASED");
    }

    /** Old fencing epochs cannot commit after a reclaim (TDD 10.5, L3); the expired lease counts as infra failure. */
    @Test
    void expiredLeaseIsRecoveredAndTheOldEpochIsFencedOff() {
        UUID id = enqueue("scan:fenced");
        ClaimedJob zombie = claimOne("worker-a");
        expireLease(id);
        ClaimedJob successor = claimOne("worker-b");

        assertThat(successor.leaseEpoch()).isEqualTo(zombie.leaseEpoch() + 2);
        assertThat(job(id)).containsEntry("infra_failure_count", 1).containsEntry("claim_count", 2)
                .containsEntry("last_error_code", "LEASE_EXPIRED");
        assertThat(admin.queryForObject("SELECT outcome FROM app.job_attempt WHERE job_id = ? AND lease_epoch = ?",
                String.class, id, zombie.leaseEpoch())).isEqualTo("LEASE_LOST");

        assertThatThrownBy(() -> commitSucceeded(zombie)).isInstanceOf(LeaseLostException.class).hasMessage("LEASE_LOST");
        assertThatThrownBy(() -> commitFailure(zombie, JobFailure.fatal("BOOM", null)))
                .isInstanceOf(LeaseLostException.class);
        commitSucceeded(successor);
        assertThat(job(id)).containsEntry("status", "SUCCEEDED");
        assertThatThrownBy(() -> commitSucceeded(successor)).isInstanceOf(LeaseLostException.class)
                .hasMessage("JOB_NOT_LEASED");
    }

    @Test
    void repeatedInfrastructureFailuresEventuallyDeadLetter() {
        UUID id = enqueue("scan:infra");
        for (int i = 0; i < 5; i++) {
            claimOne("worker-" + i);
            expireLease(id);
        }
        assertThat(claim("worker-final", 1)).isEmpty();
        assertThat(job(id)).containsEntry("status", "DEAD").containsEntry("dead_reason", "INFRA_FAILURES")
                .containsEntry("infra_failure_count", 5);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.outbox_event WHERE tenant_id = ? AND event_type = 'JobDead' AND aggregate_id = ?
                """, Integer.class, tenant, aggregate)).isEqualTo(1);
    }

    /** Bounded retries with exponential backoff, then DEAD with exactly one JobDead event and an audit row. */
    @Test
    void retryableFailuresBackOffAndDeadLetterAfterTheBudget() {
        UUID id = enqueue("scan:retry");
        commitFailure(claimOne("w1"), JobFailure.retryable("SCANNER_ERROR", null));
        assertThat(job(id)).containsEntry("status", "QUEUED").containsEntry("retryable_failure_count", 1)
                .containsEntry("backed_off", true).containsEntry("last_error_code", "SCANNER_ERROR");
        assertThat(claim("w2", 1)).as("not available during backoff").isEmpty();

        makeAvailable(id);
        commitFailure(claimOne("w2"), JobFailure.retryable("SCANNER_ERROR", null));
        makeAvailable(id);
        commitFailure(claimOne("w3"), JobFailure.retryable("SCANNER_ERROR", null));

        assertThat(job(id)).containsEntry("status", "DEAD").containsEntry("dead_reason", "RETRIES_EXHAUSTED")
                .containsEntry("retryable_failure_count", 3).containsEntry("attempt_count", 3);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.outbox_event WHERE tenant_id = ? AND event_type = 'JobDead'
                  AND payload ->> 'jobId' = ? AND payload ->> 'reason' = 'RETRIES_EXHAUSTED'
                """, Integer.class, tenant, id.toString())).isEqualTo(1);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.audit_event WHERE tenant_id = ? AND action = 'JOB_DEAD' AND resource_id = ?
                """, Integer.class, tenant, id)).isEqualTo(1);
        assertThat(admin.queryForList("SELECT outcome FROM app.job_attempt WHERE job_id = ? ORDER BY lease_epoch",
                String.class, id)).containsExactly("RETRYABLE_FAILURE", "RETRYABLE_FAILURE", "RETRYABLE_FAILURE");

        // A dead job is never modified again, by any role (TDD 10.1).
        assertThatThrownBy(() -> admin.update("UPDATE app.job SET status = 'QUEUED' WHERE id = ?", id))
                .hasMessageContaining("is final");
    }

    @Test
    void fatalFailureDeadLettersAtOnceAndDependencyOutagesDoNotUseTheBudget() {
        UUID fatal = enqueue("scan:fatal");
        commitFailure(claimOne("w1"), JobFailure.fatal("UNSUPPORTED_CONTENT", null));
        assertThat(job(fatal)).containsEntry("status", "DEAD").containsEntry("dead_reason", "FATAL_FAILURE");

        UUID deferred = enqueue("scan:deferred");
        for (int i = 0; i < 5; i++) {
            commitFailure(claimOne("w" + i), JobFailure.dependencyUnavailable("SCANNER_UNAVAILABLE", Duration.ZERO));
        }
        assertThat(job(deferred)).containsEntry("status", "QUEUED").containsEntry("deferral_count", 5)
                .containsEntry("retryable_failure_count", 0);
    }

    /** Cancellation raises the epoch, so the running worker can no longer commit (TDD 9.9, L8). */
    @Test
    void cancellationFencesOffARunningWorker() {
        UUID queued = enqueue("scan:queued");
        UUID running = enqueue("scan:running");
        ClaimedJob job = claim("worker-a", 2).stream().map(JdbcJobStore.Claim::job)
                .filter(j -> j.id().equals(running)).findFirst()
                .orElseGet(() -> claimOne("worker-a"));
        assertThat(inTenant(() -> store.cancelForAggregate(aggregate))).isEqualTo(2);
        assertThat(job(queued)).containsEntry("status", "CANCELLED");
        assertThat(job(running)).containsEntry("status", "CANCELLED");
        assertThatThrownBy(() -> commitSucceeded(job)).isInstanceOf(LeaseLostException.class)
                .hasMessage("JOB_NOT_LEASED");
    }

    /** Tenant isolation: a worker inside another tenant's context sees, claims and commits nothing of this tenant. */
    @Test
    void workersCannotReachAnotherTenantsJobs() {
        UUID id = enqueue("scan:isolated");
        UUID otherTenant = WorkerContext.newTenant("jobs-other");
        assertThat(inTenant(otherTenant, () -> store.claim("intruder", List.of(TYPE), 10))).isEmpty();
        ClaimedJob job = claimOne("worker-a");
        assertThatThrownBy(() -> inTenant(otherTenant, () -> {
            store.assertLease(job);
            return null;
        })).isInstanceOf(LeaseLostException.class);
        assertThat(inTenant(otherTenant, () -> store.cancelForAggregate(aggregate))).isZero();
        assertThat(job(id)).containsEntry("status", "LEASED");
        // Without any tenant context nothing is visible at all (fail closed).
        List<JdbcJobStore.Claim> withoutTenant = tx.execute(status -> store.claim("no-tenant", List.of(TYPE), 10));
        assertThat(withoutTenant).isEmpty();
    }

    /** Only the worker login can claim or finish jobs; the API login has no access to the queue. */
    @Test
    void apiLoginCannotClaimOrForgeJobs() throws Exception {
        UUID id = enqueue("scan:privileges");
        try (Connection app = TestDatabase.get().appConnection(); Statement statement = app.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            assertThatThrownBy(() -> statement.executeQuery("SELECT id FROM app.job"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE app.job SET status = 'LEASED', lease_owner = 'api', lease_epoch = 99 WHERE id = '" + id + "'"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO app.job (tenant_id, job_type, idempotency_key, "
                    + "aggregate_type, aggregate_id) VALUES ('" + tenant + "', 'DOCUMENT_SCAN', 'x', 'DOCUMENT', '"
                    + aggregate + "')")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE app.job_type SET max_attempts = 20"))
                    .hasMessageContaining("permission denied");
        }
        try (Connection worker = TestDatabase.get().workerConnection(); Statement statement = worker.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE app.job SET max_attempts = 20 WHERE id = '" + id + "'"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM app.job WHERE id = '" + id + "'"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE app.job_type SET max_attempts = 20"))
                    .hasMessageContaining("permission denied");
        }
    }

    /** The worker loop end to end: execute under a lease, fenced commit, crash recovery with a single effect. */
    @Test
    void workerRecoversFromACrashAndCommitsTheEffectOnce() {
        OutboxPublisher outbox = WorkerContext.bean(OutboxPublisher.class);
        AtomicInteger executions = new AtomicInteger();
        JobHandler<String> handler = new JobHandler<>() {
            @Override
            public String jobType() {
                return TYPE;
            }

            @Override
            public String execute(ClaimedJob job) {
                executions.incrementAndGet();
                return "ok";
            }

            @Override
            public void commit(ClaimedJob job, String result) {
                outbox.publish("DOCUMENT", job.aggregateId(), "ManualIntakeEntered", Map.of("jobId", job.id().toString()));
            }
        };
        JobWorker worker = new JobWorker(store, WorkerContext.bean(TenantLookup.class), List.of(handler),
                WorkerContext.bean(PlatformTransactionManager.class), 1);
        try {
            UUID id = enqueue("scan:crash");
            // A worker claims the job and dies before doing anything: its lease simply expires.
            claimOne("crashed-worker");
            expireLease(id);

            List<JdbcJobStore.Claim> claims = worker.claim(tenant, 5);
            assertThat(claims).hasSize(1);
            worker.process(claims.get(0));

            assertThat(job(id)).containsEntry("status", "SUCCEEDED").containsEntry("infra_failure_count", 1);
            assertThat(executions).hasValue(1);
            assertThat(admin.queryForObject("""
                    SELECT count(*) FROM app.outbox_event WHERE tenant_id = ? AND aggregate_id = ? AND event_type = 'ManualIntakeEntered'
                    """, Integer.class, tenant, aggregate)).isEqualTo(1);
            assertThat(worker.claim(tenant, 5)).isEmpty();
        } finally {
            try {
                worker.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void workerRecordsHandlerFailuresUnderTheLease() {
        JobHandler<Void> failing = new JobHandler<>() {
            @Override
            public String jobType() {
                return TYPE;
            }

            @Override
            public Void execute(ClaimedJob job) throws JobFailure {
                throw JobFailure.fatal("UNSUPPORTED_CONTENT", null);
            }

            @Override
            public void commit(ClaimedJob job, Void result) {
                throw new AssertionError("never committed");
            }
        };
        JobWorker worker = new JobWorker(store, WorkerContext.bean(TenantLookup.class), List.of(failing),
                WorkerContext.bean(PlatformTransactionManager.class), 1);
        try {
            UUID id = enqueue("scan:fatal-worker");
            worker.process(worker.claim(tenant, 1).get(0));
            assertThat(job(id)).containsEntry("status", "DEAD").containsEntry("dead_reason", "FATAL_FAILURE")
                    .containsEntry("last_error_code", "UNSUPPORTED_CONTENT");
        } finally {
            try {
                worker.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
