package io.github.tony079.sdlc.workflow.internal;

import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Engine reaction to a dead job (TDD 9.2, 10.6): the job's stage execution becomes FAILED, and a dead repository
 * validation job moves the requirement to FAILED (T09, R1-M4). T19 (BA analysis) arrives with R1-M5.
 */
@Component
class JobDeadConsumer implements EventConsumer {

    private final JdbcClient jdbc;
    private final JdbcWorkflowEngine engine;

    JobDeadConsumer(JdbcClient jdbc, JdbcWorkflowEngine engine) {
        this.jdbc = jdbc;
        this.engine = engine;
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
        // T09 (R1-M4): a dead repository validation job fails the workflow. No retry path exists (O-27, no T30).
        if ("REPO_VALIDATE".equals(event.payload().get("jobType")) && "REQUIREMENT".equals(event.aggregateType())) {
            engine.failRepositoryValidation(event.aggregateId());
        }
        // T19 (R1-M5): a dead BA job (analysis or commit) fails the workflow; no admin retry exists (O-27, T26 not built).
        Object jobType = event.payload().get("jobType");
        if (("BA_ANALYSIS".equals(jobType) || "BA_COMMIT_RESULT".equals(jobType))
                && "REQUIREMENT".equals(event.aggregateType())) {
            engine.failAnalysis(event.aggregateId());
        }
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
