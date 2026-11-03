package io.github.tony079.sdlc.workflow.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
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

    private final JdbcClient jdbc;
    private final AuditWriter audit;

    JdbcWorkflowEngine(JdbcClient jdbc, AuditWriter audit) {
        this.jdbc = jdbc;
        this.audit = audit;
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
    public WorkflowState transition(UUID requirementId, Transition transition, String reason) {
        // Lock the instance: concurrent transitions of one requirement are applied one after the other.
        Current current = jdbc.sql("""
                SELECT id, projected_state, last_seq FROM app.workflow_instance
                WHERE requirement_id = :requirement FOR UPDATE
                """)
                .param("requirement", requirementId)
                .query((rs, n) -> new Current(rs.getObject("id", UUID.class),
                        WorkflowState.valueOf(rs.getString("projected_state")), rs.getInt("last_seq")))
                .single();

        WorkflowState target = TransitionTable.target(transition, current.state()).orElse(null);
        if (target == null) {
            // Survives the caller's rollback (decision: refused attempts stay on record).
            audit.recordIndependently(AuditAction.ILLEGAL_TRANSITION_ATTEMPTED, "REQUIREMENT", requirementId,
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
