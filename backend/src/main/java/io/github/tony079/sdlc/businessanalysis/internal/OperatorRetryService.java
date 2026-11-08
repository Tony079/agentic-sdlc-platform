package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffAccess.StaffContext;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator retry after review (owner decision O-27; PRD T26). Tenant-scoped, never generic:
 * <ul>
 *   <li>a staff member with JOB_RETRY and an assignment to the active tenant lists the tenant's dead jobs (content-free
 *       columns only) and asks for one dead BA job of a FAILED requirement to be retried, with a reason;</li>
 *   <li>permanent failures are not retried: a FATAL failure whose code is a policy or authorisation outcome (for
 *       example COST_CAP) is refused, as are job types without an approved retry transition (repository validation,
 *       PC-01 not approved);</li>
 *   <li>the worker re-validates the request when it is delivered (pending, requirement still FAILED, the job still
 *       dead) and performs T26 with a new input set, or rejects it. Every request and outcome is audited.</li>
 * </ul>
 * Lease and fencing are unchanged: the retry creates a new stage execution and a new job; the dead job stays dead.
 */
@Component
@Profile("!migrate & !provision")
class OperatorRetryService implements EventConsumer {

    static final Set<String> RETRYABLE_TYPES = Set.of("BA_ANALYSIS", "BA_COMMIT_RESULT");
    static final Set<String> PERMANENT_CODES = Set.of("COST_CAP", "FORBIDDEN", "POLICY_VIOLATION", "INVALID_INPUT",
            "PAYLOAD_INVALID");

    record DeadJob(UUID id, String jobType, UUID aggregateId, String lastErrorCode, String deadReason, int attempts,
                   OffsetDateTime finishedAt, boolean retryable) {
    }

    private final JdbcClient jdbc;
    private final StaffAccess staffAccess;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final WorkflowEngine workflow;
    private final AnalysisStarter starter;

    OperatorRetryService(JdbcClient jdbc, StaffAccess staffAccess, OutboxPublisher outbox, AuditWriter audit,
                         WorkflowEngine workflow, AnalysisStarter starter) {
        this.jdbc = jdbc;
        this.staffAccess = staffAccess;
        this.outbox = outbox;
        this.audit = audit;
        this.workflow = workflow;
        this.starter = starter;
    }

    @Transactional(readOnly = true)
    public List<DeadJob> deadJobs() {
        staffAccess.requireCapability(StaffCapability.JOB_RETRY);
        return jdbc.sql("""
                SELECT id, job_type, aggregate_id, last_error_code, dead_reason, attempt_count, finished_at
                FROM app.dead_job ORDER BY finished_at DESC LIMIT 200
                """).query((rs, n) -> new DeadJob(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getInt(6),
                rs.getObject(7, OffsetDateTime.class), retryable(rs.getString(2), rs.getString(5), rs.getString(4))))
                .list();
    }

    @Transactional
    public Map<String, String> requestRetry(UUID jobId, String reason) {
        StaffContext staff = staffAccess.requireCapability(StaffCapability.JOB_RETRY);
        String text = reason == null ? "" : reason.strip();
        if (text.length() < 10 || text.length() > 1000) {
            throw ApiException.validation(List.of(new FieldError("reason", "INVALID_LENGTH")));
        }
        DeadJob job = jdbc.sql("""
                SELECT id, job_type, aggregate_id, last_error_code, dead_reason, attempt_count, finished_at
                FROM app.dead_job WHERE id = :id
                """).param("id", jobId).query((rs, n) -> new DeadJob(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getInt(6),
                rs.getObject(7, OffsetDateTime.class), false)).optional().orElseThrow(ApiException::notFound);
        if (!retryable(job.jobType(), job.deadReason(), job.lastErrorCode())) {
            throw ApiException.conflict("PERMANENT_FAILURE",
                    "This failure is permanent or has no approved retry path; it cannot be retried.");
        }
        if (workflow.state(job.aggregateId()) != WorkflowState.FAILED) {
            throw ApiException.conflict("NOT_FAILED", "Only a requirement in FAILED can be retried.");
        }
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO app.admin_retry_request (tenant_id, job_id, requirement_id, requested_by, reason)
                    VALUES (:t, :j, :r, :s, :reason) RETURNING id
                    """).param("t", staff.tenantId()).param("j", jobId).param("r", job.aggregateId())
                    .param("s", staff.staffId()).param("reason", text).query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("RETRY_ALREADY_REQUESTED", "A retry of this job is already pending.");
        }
        outbox.publish("REQUIREMENT", job.aggregateId(), EventTypes.ADMIN_RETRY_REQUESTED,
                Map.of("requirementId", job.aggregateId().toString(), "retryRequestId", id.toString()));
        audit.record(AuditAction.ADMIN_RETRY_REQUESTED, "JOB", jobId, AuditResult.SUCCESS);
        return Map.of("retryRequestId", id.toString(), "status", "PENDING");
    }

    static boolean retryable(String jobType, String deadReason, String lastErrorCode) {
        if (!RETRYABLE_TYPES.contains(jobType)) {
            return false;
        }
        return !("FATAL_FAILURE".equals(deadReason) && lastErrorCode != null && PERMANENT_CODES.contains(lastErrorCode));
    }

    // ---- worker: re-validation and T26 ----------------------------------------------------------------------

    @Override
    public String name() {
        return "operator-retry";
    }

    @Override
    public boolean accepts(String eventType) {
        return EventTypes.ADMIN_RETRY_REQUESTED.equals(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        UUID requestId = event.uuid("retryRequestId");
        UUID requirementId = event.uuid("requirementId");
        record Pending(UUID jobId, String status) {
        }
        Pending request = jdbc.sql("SELECT job_id, status FROM app.admin_retry_request WHERE id = :id")
                .param("id", requestId).query((rs, n) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2)))
                .optional().orElse(null);
        if (request == null || !"PENDING".equals(request.status())) {
            return;
        }
        String jobStatus = jdbc.sql("SELECT status FROM app.job WHERE id = :id").param("id", request.jobId())
                .query(String.class).single();
        String outcome;
        if (!"DEAD".equals(jobStatus)) {
            outcome = "JOB_NOT_DEAD";
        } else {
            outcome = starter.startRetry(requirementId).isPresent() ? null : "NOT_RETRYABLE_NOW";
        }
        jdbc.sql("""
                UPDATE app.admin_retry_request SET status = :status, outcome_code = :code, decided_at = clock_timestamp()
                WHERE id = :id
                """).param("status", outcome == null ? "COMPLETED" : "REJECTED")
                .param("code", outcome == null ? "T26_STARTED" : outcome).param("id", requestId).update();
        outbox.publish("REQUIREMENT", requirementId, outcome == null ? EventTypes.ADMIN_RETRY_COMPLETED
                : EventTypes.ADMIN_RETRY_REJECTED, Map.of("requirementId", requirementId.toString(),
                "retryRequestId", requestId.toString()));
        audit.record(outcome == null ? AuditAction.ADMIN_RETRY_COMPLETED : AuditAction.ADMIN_RETRY_REJECTED,
                "REQUIREMENT", requirementId, outcome == null ? AuditResult.SUCCESS : AuditResult.DENIED);
    }
}
