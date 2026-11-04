package io.github.tony079.sdlc.workflow.internal;

import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.queue.api.JobQueue;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Cancellation follow-up (TDD 9.9): when RequirementCancelled is delivered, unfinished stage executions are
 * marked CANCELLED and the requirement's jobs are cancelled with a raised epoch, so a worker still running one can
 * no longer commit. History is kept. Runs in the worker, which holds the queue privileges the API lacks.
 */
@Component
class CancellationConsumer implements EventConsumer {

    private final JdbcClient jdbc;
    private final JobQueue jobs;
    private final AuditWriter audit;

    CancellationConsumer(JdbcClient jdbc, JobQueue jobs, AuditWriter audit) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.audit = audit;
    }

    @Override
    public String name() {
        return "workflow-cancellation";
    }

    @Override
    public boolean accepts(String eventType) {
        return EventTypes.REQUIREMENT_CANCELLED.equals(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        UUID requirement = event.uuid("requirementId");
        jdbc.sql("""
                UPDATE app.stage_execution s SET status = 'CANCELLED', updated_at = clock_timestamp()
                FROM app.workflow_instance i
                WHERE s.instance_id = i.id AND i.requirement_id = :requirement
                  AND s.status IN ('PENDING', 'BLOCKED', 'READY', 'RUNNING', 'WAITING_HUMAN')
                """).param("requirement", requirement).update();
        if (jobs.cancelForAggregate(requirement) > 0) {
            audit.record(AuditAction.JOBS_CANCELLED, "REQUIREMENT", requirement, AuditResult.SUCCESS);
        }
    }
}
