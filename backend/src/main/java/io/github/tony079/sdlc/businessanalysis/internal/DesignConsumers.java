package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * GD-26 worker reactions: TddRequested enqueues TDD_GENERATION (the API cannot touch the job queue); a dead
 * TDD_GENERATION job marks the design FAILED with its error code and tells the person who asked for it.
 */
@Component
@Profile("!migrate & !provision")
class DesignConsumers implements EventConsumer {

    private final JobQueue jobs;
    private final DesignRepository designs;
    private final ReviewRepository reviews;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final ActivityLog activity;

    DesignConsumers(JobQueue jobs, DesignRepository designs, ReviewRepository reviews, AuditWriter audit,
                    JdbcClient jdbc, ActivityLog activity) {
        this.activity = activity;
        this.jobs = jobs;
        this.designs = designs;
        this.reviews = reviews;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "technical-design";
    }

    @Override
    public boolean accepts(String eventType) {
        return EventTypes.TDD_REQUESTED.equals(eventType) || EventTypes.JOB_DEAD.equals(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        if (EventTypes.TDD_REQUESTED.equals(event.eventType())) {
            UUID versionId = event.uuid("tddVersionId");
            String attempt = String.valueOf(event.payload().get("attempt"));
            jobs.enqueue(new JobRequest(DesignGenerationHandler.JOB_TYPE, "tdd:" + versionId + ":" + attempt,
                    "REQUIREMENT", event.aggregateId(), null, Map.of("tddVersionId", versionId.toString()), 100));
            return;
        }
        Object jobType = event.payload().get("jobType");
        if (("BA_ANALYSIS".equals(jobType) || "BA_COMMIT_RESULT".equals(jobType))
                && "REQUIREMENT".equals(event.aggregateType())) {
            // GD-29: the workflow marks the analysis FAILED (T19); the timeline says so.
            activity.fail(event.aggregateId(), ActivityLog.Stage.BRD,
                    event.payload().get("stageExecutionId") == null ? null : event.uuid("stageExecutionId"),
                    "The analysis stopped after its automatic retries. The author can retry it from this page.");
            return;
        }
        if (!DesignGenerationHandler.JOB_TYPE.equals(jobType)) {
            return;
        }
        String code = jdbc.sql("SELECT last_error_code FROM app.job WHERE id = :id").param("id", event.uuid("jobId"))
                .query(String.class).optional().filter(c -> c.matches("^[A-Z][A-Z0-9_]{1,63}$"))
                .orElse("DESIGN_FAILED");
        designs.open(event.aggregateId(), true).filter(v -> "GENERATING".equals(v.status())).ifPresent(v -> {
            designs.fail(v.id(), code);
            activity.fail(v.requirementId(), ActivityLog.Stage.DESIGN, v.id(), "The design stopped after its automatic "
                    + "retries. A Technical Approver can try again from this page.");
            reviews.notify(v.tenantId(), v.requestedBy(), "TDD_FAILED", v.requirementId());
            audit.record(AuditAction.TDD_FAILED, "TDD_VERSION", v.id(), AuditResult.FAILED);
        });
    }
}
