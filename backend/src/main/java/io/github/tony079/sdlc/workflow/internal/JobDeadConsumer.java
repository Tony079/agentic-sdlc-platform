package io.github.tony079.sdlc.workflow.internal;

import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Engine reaction to a dead job (TDD 9.2, 10.6): the job's stage execution becomes FAILED. The workflow state is
 * not changed here; the workflow-level FAILED transitions (T09, T19) arrive with the stages that can fail
 * (repository validation, BA analysis), whose handlers are built in their own milestones.
 */
@Component
class JobDeadConsumer implements EventConsumer {

    private final JdbcClient jdbc;

    JobDeadConsumer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "workflow-job-dead";
    }

    @Override
    public boolean accepts(String eventType) {
        return EventTypes.JOB_DEAD.equals(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        Object stage = event.payload().get("stageExecutionId");
        if (stage == null) {
            return;
        }
        jdbc.sql("""
                UPDATE app.stage_execution SET status = 'FAILED', updated_at = clock_timestamp()
                WHERE id = :stage AND status IN ('PENDING', 'BLOCKED', 'READY', 'RUNNING', 'WAITING_HUMAN')
                """).param("stage", event.uuid("stageExecutionId")).update();
    }
}
