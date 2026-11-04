package io.github.tony079.sdlc.queue.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;
import io.github.tony079.sdlc.queue.api.LeaseLostException;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Data access of the job queue (TDD 10). Every method runs in the caller's transaction and tenant context, under
 * forced RLS. All lease decisions use the database clock ({@code clock_timestamp()}) read after the row lock is
 * held (TDD 10.3, 10.8), so application clock skew never matters.
 *
 * <p>Only the worker login has the column privileges these updates need; the API login cannot claim, finish or
 * cancel jobs.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcJobStore implements JobQueue {

    private static final Logger log = LoggerFactory.getLogger(JdbcJobStore.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final MeterRegistry meters;

    JdbcJobStore(JdbcClient jdbc, JsonMapper json, OutboxPublisher outbox, AuditWriter audit, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
        this.audit = audit;
        this.meters = meters;
    }

    /** A claimed job plus the lease length used for heartbeats. */
    public record Claim(ClaimedJob job, int leaseSeconds) {
    }

    @Override
    public boolean enqueue(JobRequest request) {
        return jdbc.sql("""
                INSERT INTO app.job (tenant_id, job_type, idempotency_key, aggregate_type, aggregate_id,
                                     stage_execution_id, payload, priority)
                VALUES (:tenant, :type, :key, :aggregateType, :aggregateId, :stage, CAST(:payload AS jsonb), :priority)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """)
                .param("tenant", TenantContext.requireTenant())
                .param("type", request.jobType()).param("key", request.idempotencyKey())
                .param("aggregateType", request.aggregateType()).param("aggregateId", request.aggregateId())
                .param("stage", request.stageExecutionId())
                .param("payload", json.writeValueAsString(request.payload()))
                .param("priority", request.priority())
                .update() == 1;
    }

    @Override
    public int cancelForAggregate(UUID aggregateId) {
        jdbc.sql("""
                UPDATE app.job_attempt a SET finished_at = clock_timestamp(), outcome = 'CANCELLED', error_code = 'CANCELLED'
                FROM app.job j
                WHERE a.job_id = j.id AND j.aggregate_id = :aggregate AND j.status = 'LEASED' AND a.finished_at IS NULL
                """).param("aggregate", aggregateId).update();
        // Raising the epoch fences off any worker still running one of these jobs (TDD 9.9, test L8).
        return jdbc.sql("""
                UPDATE app.job SET status = 'CANCELLED', lease_epoch = lease_epoch + 1, lease_owner = NULL,
                       leased_at = NULL, lease_expires_at = NULL, finished_at = clock_timestamp()
                WHERE aggregate_id = :aggregate AND status IN ('QUEUED', 'LEASED')
                """).param("aggregate", aggregateId).update();
    }

    private record LockedRow(String status, boolean available, boolean expired, int infraFailures,
                             int maxInfraFailures, String jobType, String aggregateType, UUID aggregateId,
                             UUID stageExecutionId) {
    }

    /**
     * Claims up to {@code limit} jobs of the given types for {@code workerId} (TDD 10.3). Phase 1 selects and locks
     * candidates with SKIP LOCKED; phase 2 re-reads each locked row with a fresh database time and decides. An
     * expired lease is recovered first (attempt closed LEASE_LOST, infrastructure failure counted, epoch raised);
     * a live lease is never taken. Requires READ COMMITTED.
     */
    public List<Claim> claim(String workerId, List<String> jobTypes, int limit) {
        if (jobTypes.isEmpty() || limit < 1) {
            return List.of();
        }
        String isolation = jdbc.sql("SHOW transaction_isolation").query(String.class).single();
        if (!"read committed".equals(isolation)) {
            throw new IllegalStateException("Job claiming requires READ COMMITTED, not " + isolation);
        }
        List<UUID> candidates = jdbc.sql("""
                SELECT id FROM app.job
                WHERE job_type IN (:types)
                  AND ((status = 'QUEUED' AND available_at <= clock_timestamp())
                    OR (status = 'LEASED' AND lease_expires_at <= clock_timestamp()))
                ORDER BY (status = 'LEASED') DESC, priority, available_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("types", jobTypes)
                .param("limit", limit)
                .query(UUID.class).list();

        List<Claim> claimed = new ArrayList<>();
        for (UUID id : candidates) {
            LockedRow row = jdbc.sql("""
                    SELECT status, available_at <= clock_timestamp() AS available,
                           coalesce(lease_expires_at <= clock_timestamp(), false) AS expired,
                           infra_failure_count, max_infra_failures, job_type, aggregate_type, aggregate_id,
                           stage_execution_id
                    FROM app.job WHERE id = :id
                    """)
                    .param("id", id)
                    .query((rs, n) -> new LockedRow(rs.getString("status"), rs.getBoolean("available"),
                            rs.getBoolean("expired"), rs.getInt("infra_failure_count"),
                            rs.getInt("max_infra_failures"), rs.getString("job_type"), rs.getString("aggregate_type"),
                            rs.getObject("aggregate_id", UUID.class),
                            rs.getObject("stage_execution_id", UUID.class)))
                    .single();
            if ("LEASED".equals(row.status())) {
                if (!row.expired()) {
                    continue;
                }
                if (!recoverExpiredLease(id, row)) {
                    continue;
                }
            } else if (!"QUEUED".equals(row.status()) || !row.available()) {
                continue;
            }
            claimed.add(lease(id, workerId));
        }
        return claimed;
    }

    /** Returns true if the job was requeued (and may be claimed now), false if it went DEAD. */
    private boolean recoverExpiredLease(UUID id, LockedRow row) {
        jdbc.sql("""
                UPDATE app.job_attempt SET finished_at = clock_timestamp(), outcome = 'LEASE_LOST', error_code = 'LEASE_EXPIRED'
                WHERE job_id = :id AND finished_at IS NULL
                """).param("id", id).update();
        int infra = row.infraFailures() + 1;
        meters.counter("sdlc.jobs.lease.expired", "job_type", row.jobType()).increment();
        if (infra >= row.maxInfraFailures()) {
            jdbc.sql("""
                    UPDATE app.job SET status = 'DEAD', dead_reason = 'INFRA_FAILURES', infra_failure_count = :infra,
                           lease_epoch = lease_epoch + 1, lease_owner = NULL, leased_at = NULL, lease_expires_at = NULL,
                           last_error_code = 'LEASE_EXPIRED', finished_at = clock_timestamp()
                    WHERE id = :id
                    """).param("infra", infra).param("id", id).update();
            jobDead(id, row.jobType(), row.aggregateType(), row.aggregateId(), row.stageExecutionId(), "INFRA_FAILURES");
            return false;
        }
        jdbc.sql("""
                UPDATE app.job SET status = 'QUEUED', infra_failure_count = :infra, lease_epoch = lease_epoch + 1,
                       lease_owner = NULL, leased_at = NULL, lease_expires_at = NULL, available_at = clock_timestamp(),
                       last_error_code = 'LEASE_EXPIRED'
                WHERE id = :id
                """).param("infra", infra).param("id", id).update();
        log.warn("Recovered expired job lease: job={} type={} infraFailures={}", id, row.jobType(), infra);
        return true;
    }

    private Claim lease(UUID id, String workerId) {
        Claim claim = jdbc.sql("""
                UPDATE app.job SET status = 'LEASED', lease_owner = :worker, lease_epoch = lease_epoch + 1,
                       leased_at = clock_timestamp(), lease_expires_at = clock_timestamp() + make_interval(secs => lease_seconds),
                       claim_count = claim_count + 1, attempt_count = attempt_count + 1
                WHERE id = :id AND status = 'QUEUED'
                RETURNING id, tenant_id, job_type, aggregate_type, aggregate_id, stage_execution_id, payload::text AS payload,
                          lease_epoch, attempt_count, lease_seconds
                """)
                .param("worker", workerId).param("id", id)
                .query((rs, n) -> new Claim(new ClaimedJob(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("job_type"), rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class), rs.getObject("stage_execution_id", UUID.class),
                        Map.copyOf(json.readValue(rs.getString("payload"), MAP)), workerId, rs.getLong("lease_epoch"),
                        rs.getInt("attempt_count")), rs.getInt("lease_seconds")))
                .single();
        jdbc.sql("""
                INSERT INTO app.job_attempt (tenant_id, job_id, lease_epoch, worker_id)
                VALUES (:tenant, :job, :epoch, :worker)
                """)
                .param("tenant", claim.job().tenantId()).param("job", id)
                .param("epoch", claim.job().leaseEpoch()).param("worker", workerId)
                .update();
        meters.counter("sdlc.jobs.claimed", "job_type", claim.job().jobType()).increment();
        return claim;
    }

    /**
     * Extends a live lease (TDD 10.4). Succeeds only for the same owner and epoch, while the lease has not expired
     * and the job's maximum runtime is not exceeded. Returns false otherwise; the worker must then stop.
     */
    public boolean heartbeat(ClaimedJob job) {
        return jdbc.sql("""
                UPDATE app.job
                SET lease_expires_at = least(clock_timestamp() + make_interval(secs => lease_seconds),
                                             leased_at + make_interval(secs => max_runtime_seconds))
                WHERE id = :id AND status = 'LEASED' AND lease_owner = :worker AND lease_epoch = :epoch
                  AND lease_expires_at > clock_timestamp()
                  AND leased_at + make_interval(secs => max_runtime_seconds) > clock_timestamp()
                """)
                .param("id", job.id()).param("worker", job.workerId()).param("epoch", job.leaseEpoch())
                .update() == 1;
    }

    /**
     * Fenced commit, step 1 (TDD 10.5): locks the job row and verifies this worker still holds the lease with the
     * same epoch, then checks expiry with a database time read after the lock. Holding the row lock until commit
     * means the lease cannot be reclaimed in between.
     *
     * @throws LeaseLostException LEASE_LOST, LEASE_EXPIRED or JOB_NOT_LEASED; the caller's transaction must roll back
     */
    public void assertLease(ClaimedJob job) {
        record Holder(String status, String owner, long epoch) {
        }
        Holder holder = jdbc.sql("SELECT status, lease_owner, lease_epoch FROM app.job WHERE id = :id FOR UPDATE")
                .param("id", job.id())
                .query((rs, n) -> new Holder(rs.getString(1), rs.getString(2), rs.getLong(3)))
                .optional()
                .orElseThrow(() -> new LeaseLostException("LEASE_LOST"));
        if (!"LEASED".equals(holder.status())) {
            throw new LeaseLostException("JOB_NOT_LEASED");
        }
        if (!job.workerId().equals(holder.owner()) || job.leaseEpoch() != holder.epoch()) {
            throw new LeaseLostException("LEASE_LOST");
        }
        boolean live = jdbc.sql("SELECT lease_expires_at > clock_timestamp() FROM app.job WHERE id = :id")
                .param("id", job.id()).query(Boolean.class).single();
        if (!live) {
            throw new LeaseLostException("LEASE_EXPIRED");
        }
    }

    /** Fenced commit, step 2 for success. Call only after {@link #assertLease} in the same transaction. */
    public void finishSucceeded(ClaimedJob job) {
        closeAttempt(job, "SUCCEEDED", null);
        jdbc.sql("""
                UPDATE app.job SET status = 'SUCCEEDED', lease_owner = NULL, leased_at = NULL, lease_expires_at = NULL,
                       finished_at = clock_timestamp()
                WHERE id = :id AND lease_epoch = :epoch
                """).param("id", job.id()).param("epoch", job.leaseEpoch()).update();
        meters.counter("sdlc.jobs.succeeded", "job_type", job.jobType()).increment();
    }

    private record Policy(int retryableFailures, int maxAttempts, int base, int cap) {
    }

    /**
     * Fenced failure recording (TDD 10.6). Call only after {@link #assertLease} in the same transaction.
     * RETRYABLE uses the retry budget (DEAD when it is used up), FATAL goes DEAD at once, DEPENDENCY_UNAVAILABLE is
     * deferred without using the budget.
     */
    public void finishFailure(ClaimedJob job, JobFailure failure) {
        Policy policy = jdbc.sql("""
                SELECT retryable_failure_count, max_attempts, backoff_base_seconds, backoff_cap_seconds
                FROM app.job WHERE id = :id
                """)
                .param("id", job.id())
                .query((rs, n) -> new Policy(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)))
                .single();
        switch (failure.kind()) {
            case FATAL -> {
                closeAttempt(job, "FATAL_FAILURE", failure.code());
                markDead(job, "FATAL_FAILURE", failure.code(), policy.retryableFailures());
            }
            case RETRYABLE -> {
                closeAttempt(job, "RETRYABLE_FAILURE", failure.code());
                int failures = policy.retryableFailures() + 1;
                if (failures >= policy.maxAttempts()) {
                    markDead(job, "RETRIES_EXHAUSTED", failure.code(), failures);
                } else {
                    requeue(job, failure.code(), backoffSeconds(policy.base(), policy.cap(), failures), failures, false);
                    meters.counter("sdlc.jobs.retried", "job_type", job.jobType()).increment();
                }
            }
            case DEPENDENCY_UNAVAILABLE -> {
                closeAttempt(job, "DEPENDENCY_UNAVAILABLE", failure.code());
                requeue(job, failure.code(), failure.delay().toMillis() / 1000.0, policy.retryableFailures(), true);
                meters.counter("sdlc.jobs.deferred", "job_type", job.jobType()).increment();
            }
        }
    }

    private void requeue(ClaimedJob job, String code, double delaySeconds, int retryableFailures, boolean deferral) {
        jdbc.sql("""
                UPDATE app.job SET status = 'QUEUED', lease_owner = NULL, leased_at = NULL, lease_expires_at = NULL,
                       retryable_failure_count = :failures, deferral_count = deferral_count + :deferral,
                       last_error_code = :code, available_at = clock_timestamp() + make_interval(secs => :delay)
                WHERE id = :id AND lease_epoch = :epoch
                """)
                .param("failures", retryableFailures).param("deferral", deferral ? 1 : 0).param("code", code)
                .param("delay", delaySeconds).param("id", job.id()).param("epoch", job.leaseEpoch())
                .update();
    }

    private void markDead(ClaimedJob job, String reason, String code, int retryableFailures) {
        jdbc.sql("""
                UPDATE app.job SET status = 'DEAD', dead_reason = :reason, last_error_code = :code,
                       retryable_failure_count = :failures, lease_owner = NULL, leased_at = NULL,
                       lease_expires_at = NULL, finished_at = clock_timestamp()
                WHERE id = :id AND lease_epoch = :epoch
                """)
                .param("reason", reason).param("code", code).param("failures", retryableFailures)
                .param("id", job.id()).param("epoch", job.leaseEpoch())
                .update();
        jobDead(job.id(), job.jobType(), job.aggregateType(), job.aggregateId(), job.stageExecutionId(), reason);
    }

    /** JobDead is published in the same transaction as the DEAD state (TDD 10.6), ordered with its aggregate. */
    private void jobDead(UUID jobId, String jobType, String aggregateType, UUID aggregateId, UUID stageExecutionId,
                         String reason) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("jobId", jobId.toString());
        if (stageExecutionId != null) {
            attributes.put("stageExecutionId", stageExecutionId.toString());
        }
        attributes.put("jobType", jobType);
        attributes.put("reason", reason);
        outbox.publish(aggregateType, aggregateId, EventTypes.JOB_DEAD, attributes);
        audit.record(AuditAction.JOB_DEAD, "JOB", jobId, AuditResult.FAILED);
        meters.counter("sdlc.jobs.dead", "job_type", jobType).increment();
        log.error("ALERT job dead: job={} type={} aggregate={} tenant={} reason={}", jobId, jobType, aggregateId,
                TenantContext.requireTenant(), reason);
    }

    private void closeAttempt(ClaimedJob job, String outcome, String code) {
        jdbc.sql("""
                UPDATE app.job_attempt SET finished_at = clock_timestamp(), outcome = :outcome, error_code = :code
                WHERE job_id = :id AND lease_epoch = :epoch AND finished_at IS NULL
                """)
                .param("outcome", outcome).param("code", code).param("id", job.id()).param("epoch", job.leaseEpoch())
                .update();
    }

    /** min(cap, base * 2^(n-1)) seconds, multiplied by a jitter factor in [0.5, 1.5) (TDD 10.6). */
    static double backoffSeconds(int base, int cap, int failures) {
        double raw = Math.min(cap, base * Math.pow(2, Math.min(failures - 1, 30)));
        return raw * ThreadLocalRandom.current().nextDouble(0.5, 1.5);
    }
}
