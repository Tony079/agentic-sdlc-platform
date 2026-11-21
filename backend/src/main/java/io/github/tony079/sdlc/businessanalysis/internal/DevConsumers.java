package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.queue.api.JobQueue;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * GD-36 worker reactions: a person's control (pause, resume, retry, skip) makes the orchestrator look at once; a
 * DEV_STORY job that used up its retries marks its story FAILED so a person can decide.
 */
@Component
@Profile("!migrate & !provision")
class DevConsumers implements EventConsumer {

    private final JobQueue jobs;
    private final DevRepository devs;
    private final ActivityLog activity;
    private final JdbcClient jdbc;
    private final org.springframework.beans.factory.ObjectProvider<DevOrchestrator> orchestrator;
    private final PlanPushRepository pushes;

    DevConsumers(JobQueue jobs, DevRepository devs, ActivityLog activity, JdbcClient jdbc,
                 org.springframework.beans.factory.ObjectProvider<DevOrchestrator> orchestrator,
                 PlanPushRepository pushes) {
        this.orchestrator = orchestrator;
        this.pushes = pushes;
        this.jobs = jobs;
        this.devs = devs;
        this.activity = activity;
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "development";
    }

    @Override
    public boolean accepts(String eventType) {
        return EventTypes.DEVELOPMENT_CHANGED.equals(eventType) || EventTypes.JOB_DEAD.equals(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        if (EventTypes.DEVELOPMENT_CHANGED.equals(event.eventType()) && event.payload().get("startPlanPushId") != null) {
            // A person started development for a plan pushed before the Developer agent existed.
            DevOrchestrator o = orchestrator.getIfAvailable();
            if (o != null) {
                pushes.find(event.uuid("startPlanPushId"), false).ifPresent(o::start);
            }
            return;
        }
        if (EventTypes.DEVELOPMENT_CHANGED.equals(event.eventType())) {
            UUID run = event.uuid("devRunId");
            jobs.enqueue(DevOrchestrator.tick(run, event.aggregateId(), "e" + event.id()));
            return;
        }
        if (!DevStoryHandler.JOB_TYPE.equals(event.payload().get("jobType"))) {
            return;
        }
        Map<String, Object> row = jdbc.sql("SELECT payload->>'devTaskId' AS task, last_error_code AS code FROM app.job "
                + "WHERE id = :id").param("id", event.uuid("jobId")).query().singleRow();
        UUID taskId;
        try {
            taskId = UUID.fromString(String.valueOf(row.get("task")));
        } catch (IllegalArgumentException e) {
            return;
        }
        String code = row.get("code") instanceof String c && c.matches("^[A-Z][A-Z0-9_]{1,63}$") ? c : "DEV_FAILED";
        devs.task(taskId, true).filter(t -> "CODING".equals(t.status()) || "QUEUED".equals(t.status())).ifPresent(t -> {
            devs.failed(t.id(), code, null);
            devs.run(t.devRunId(), false).ifPresent(run -> activity.fail(run.requirementId(),
                    ActivityLog.Stage.DEVELOPMENT, run.id(), "\"" + t.title() + "\" stopped after its automatic "
                            + "retries (" + DevService.reason(code) + "). Retry it or skip it."));
        });
    }
}
