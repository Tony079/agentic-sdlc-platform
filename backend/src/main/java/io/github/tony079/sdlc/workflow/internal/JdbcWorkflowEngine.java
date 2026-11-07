package io.github.tony079.sdlc.workflow.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.Route;
import io.github.tony079.sdlc.workflow.api.TimelineEntry;
import io.github.tony079.sdlc.workflow.api.Transition;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JdbcWorkflowEngine implements WorkflowEngine {

    static final String DEFINITION_VERSION = "R1-v1";
    static final String AGGREGATE = "REQUIREMENT";

    private final JdbcClient jdbc;
    private final AuditWriter audit;
    private final OutboxPublisher outbox;
    private final JobQueue jobs;

    JdbcWorkflowEngine(JdbcClient jdbc, AuditWriter audit, OutboxPublisher outbox, JobQueue jobs) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.jobs = jobs;
    }

    private record Current(UUID instanceId, WorkflowState state, int lastSeq) {
    }

    @Override
    public void start(UUID requirementId) {
        UUID tenant = TenantContext.requireTenant();
        UUID instance = jdbc.sql("""
                INSERT INTO app.workflow_instance (tenant_id, requirement_id, definition_version, projected_state, last_seq)
                VALUES (:tenant, :requirement, :definition, 'DRAFT', 1) RETURNING id
                """)
                .param("tenant", tenant).param("requirement", requirementId).param("definition", DEFINITION_VERSION)
                .query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO app.workflow_transition (tenant_id, instance_id, seq, to_state, transition, actor_type, actor_id)
                VALUES (:tenant, :instance, 1, 'DRAFT', 'CREATE', :actorType, :actor)
                """)
                .param("tenant", tenant).param("instance", instance)
                .param("actorType", actorType()).param("actor", TenantContext.currentActor().orElse(null))
                .update();
    }

    @Override
    public WorkflowState state(UUID requirementId) {
        return jdbc.sql("SELECT projected_state FROM app.workflow_instance WHERE requirement_id = :requirement")
                .param("requirement", requirementId)
                .query((rs, n) -> WorkflowState.valueOf(rs.getString(1)))
                .single();
    }

    @Override
    public WorkflowState lockState(UUID requirementId) {
        return lock(requirementId).state();
    }

    @Override
    public WorkflowState submit(UUID requirementId, Route route) {
        WorkflowState to = apply(requirementId, Transition.T01_SUBMIT, null);
        outbox.publish(AGGREGATE, requirementId, EventTypes.REQUIREMENT_SUBMITTED,
                Map.of("requirementId", requirementId.toString(), "route", route.name()));
        return to;
    }

    @Override
    public WorkflowState transition(UUID requirementId, Transition transition, String reason) {
        if (transition != Transition.T29_CANCEL) {
            // T01 has its own command; T02/T03 are engine transitions that no user request can perform.
            throw new IllegalArgumentException("Not a user transition: " + transition);
        }
        WorkflowState to = apply(requirementId, transition, reason);
        outbox.publish(AGGREGATE, requirementId, EventTypes.REQUIREMENT_CANCELLED,
                Map.of("requirementId", requirementId.toString()));
        return to;
    }

    /**
     * Engine routing after submission (T02/T03), called by the worker's routing consumer under the tenant
     * context with no user actor. Returns empty, changing nothing, if the requirement is no longer SUBMITTED
     * (for example it was cancelled before the event was delivered): the event is obsolete, not an error.
     */
    Optional<WorkflowState> route(UUID requirementId, Route route) {
        if (TenantContext.currentActor().isPresent()) {
            throw new IllegalStateException("Routing is an engine transition and cannot run for a user");
        }
        Current current = lock(requirementId);
        if (current.state() != WorkflowState.SUBMITTED) {
            return Optional.empty();
        }
        Transition transition = route == Route.REPOSITORY ? Transition.T02_ROUTE_REPOSITORY
                : Transition.T03_ROUTE_MANUAL_INTAKE;
        WorkflowState to = applyLocked(requirementId, current, transition, null);
        if (transition == Transition.T02_ROUTE_REPOSITORY) {
            UUID stage = jdbc.sql("""
                    INSERT INTO app.stage_execution (tenant_id, instance_id, stage_key, attempt_no, status)
                    VALUES (:tenant, :instance, 'REPO_VALIDATION', 1, 'READY') RETURNING id
                    """)
                    .param("tenant", TenantContext.requireTenant()).param("instance", current.instanceId())
                    .query(UUID.class).single();
            jobs.enqueue(new JobRequest("REPO_VALIDATE", "repo-validate:" + requirementId + ":1", AGGREGATE,
                    requirementId, stage, Map.of("requirementId", requirementId.toString()), 100));
            outbox.publish(AGGREGATE, requirementId, EventTypes.REPO_VALIDATION_STARTED,
                    Map.of("requirementId", requirementId.toString(), "stageExecutionId", stage.toString()));
        } else {
            // PRD 0.4: no extraction, repository validation or BA job is scheduled in MANUAL_INTAKE. The Business
            // Reviewer notification (TDD T03) waits for staff accounts (O-06).
            outbox.publish(AGGREGATE, requirementId, EventTypes.MANUAL_INTAKE_ENTERED,
                    Map.of("requirementId", requirementId.toString()));
        }
        audit.record(AuditAction.REQUIREMENT_ROUTED, AGGREGATE, requirementId, AuditResult.SUCCESS);
        return Optional.of(to);
    }

    @Override
    public Optional<WorkflowState> completeRepositoryValidation(UUID requirementId, UUID stageExecutionId,
                                                                boolean documentDecisionNeeded) {
        requireEngine();
        Current current = lock(requirementId);
        if (current.state() != WorkflowState.REPO_VALIDATING) {
            return Optional.empty();
        }
        Transition transition = documentDecisionNeeded ? Transition.T07_REPO_VALIDATED_DOCUMENT_DECISION
                : Transition.T06_REPO_VALIDATED;
        WorkflowState to = applyLocked(requirementId, current, transition, null);
        if (stageExecutionId != null) {
            jdbc.sql("""
                    UPDATE app.stage_execution SET status = 'SUCCEEDED', updated_at = clock_timestamp()
                    WHERE id = :stage AND status IN ('PENDING', 'BLOCKED', 'READY', 'RUNNING')
                    """).param("stage", stageExecutionId).update();
        }
        outbox.publish(AGGREGATE, requirementId, documentDecisionNeeded ? EventTypes.DOCUMENT_DECISION_REQUESTED
                : EventTypes.REPO_VALIDATED, Map.of("requirementId", requirementId.toString()));
        audit.record(AuditAction.REQUIREMENT_ROUTED, AGGREGATE, requirementId, AuditResult.SUCCESS);
        return Optional.of(to);
    }

    @Override
    public Optional<WorkflowState> failRepositoryValidation(UUID requirementId) {
        requireEngine();
        Current current = lock(requirementId);
        if (current.state() != WorkflowState.REPO_VALIDATING) {
            return Optional.empty();
        }
        WorkflowState to = applyLocked(requirementId, current, Transition.T09_REPO_VALIDATION_FAILED, null);
        outbox.publish(AGGREGATE, requirementId, EventTypes.WORKFLOW_FAILED,
                Map.of("requirementId", requirementId.toString(), "code", "REPO_VALIDATION_FAILED"));
        audit.record(AuditAction.REPOSITORY_VALIDATION_FAILED, AGGREGATE, requirementId, AuditResult.FAILED);
        return Optional.of(to);
    }

    private static void requireEngine() {
        if (TenantContext.currentActor().isPresent()) {
            throw new IllegalStateException("Engine transitions cannot run for a user");
        }
    }

    private Current lock(UUID requirementId) {
        // Lock the instance: concurrent transitions of one requirement are applied one after the other.
        return jdbc.sql("""
                SELECT id, projected_state, last_seq FROM app.workflow_instance
                WHERE requirement_id = :requirement FOR UPDATE
                """)
                .param("requirement", requirementId)
                .query((rs, n) -> new Current(rs.getObject("id", UUID.class),
                        WorkflowState.valueOf(rs.getString("projected_state")), rs.getInt("last_seq")))
                .single();
    }

    private WorkflowState apply(UUID requirementId, Transition transition, String reason) {
        return applyLocked(requirementId, lock(requirementId), transition, reason);
    }

    private WorkflowState applyLocked(UUID requirementId, Current current, Transition transition, String reason) {
        WorkflowState target = TransitionTable.target(transition, current.state()).orElse(null);
        if (target == null) {
            // Survives the caller's rollback (decision: refused attempts stay on record).
            audit.recordIndependently(AuditAction.ILLEGAL_TRANSITION_ATTEMPTED, AGGREGATE, requirementId,
                    AuditResult.DENIED);
            throw ApiException.conflict("ILLEGAL_TRANSITION",
                    "This action is not allowed in the requirement's current state (" + current.state() + ").");
        }

        int seq = current.lastSeq() + 1;
        int updated = jdbc.sql("""
                UPDATE app.workflow_instance
                SET projected_state = :to, last_seq = :seq, version = version + 1, updated_at = clock_timestamp()
                WHERE id = :instance AND projected_state = :from AND last_seq = :lastSeq
                """)
                .param("to", target.name()).param("seq", seq).param("instance", current.instanceId())
                .param("from", current.state().name()).param("lastSeq", current.lastSeq())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Workflow instance changed while locked");
        }
        jdbc.sql("""
                INSERT INTO app.workflow_transition (tenant_id, instance_id, seq, prev_seq, from_state, to_state,
                                                     transition, actor_type, actor_id, reason)
                VALUES (:tenant, :instance, :seq, :prev, :from, :to, :transition, :actorType, :actor, :reason)
                """)
                .param("tenant", TenantContext.requireTenant()).param("instance", current.instanceId())
                .param("seq", seq).param("prev", current.lastSeq())
                .param("from", current.state().name()).param("to", target.name())
                .param("transition", transition.name())
                .param("actorType", actorType()).param("actor", TenantContext.currentActor().orElse(null))
                .param("reason", reason)
                .update();
        return target;
    }

    @Override
    public List<TimelineEntry> timeline(UUID requirementId) {
        return jdbc.sql("""
                SELECT t.seq, t.from_state, t.to_state, t.transition, t.actor_id, t.occurred_at
                FROM app.workflow_transition t JOIN app.workflow_instance i ON i.id = t.instance_id
                WHERE i.requirement_id = :requirement ORDER BY t.seq
                """)
                .param("requirement", requirementId)
                .query((rs, n) -> new TimelineEntry(rs.getInt("seq"),
                        rs.getString("from_state") == null ? null : WorkflowState.valueOf(rs.getString("from_state")),
                        WorkflowState.valueOf(rs.getString("to_state")), rs.getString("transition"),
                        rs.getObject("actor_id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();
    }

    private static String actorType() {
        return TenantContext.currentActor().isPresent() ? "USER" : "SYSTEM";
    }
}
