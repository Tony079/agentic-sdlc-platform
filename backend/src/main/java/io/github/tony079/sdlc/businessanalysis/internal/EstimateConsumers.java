package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
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
 * GD-33 worker reactions: EstimateRequested enqueues ESTIMATE_GENERATION; a dead ESTIMATE_GENERATION job marks the
 * estimate FAILED with its error code and tells the person who asked for it.
 */
@Component
@Profile("!migrate & !provision")
class EstimateConsumers implements EventConsumer {

    private final JobQueue jobs;
    private final EstimateRepository estimates;
    private final ReviewRepository reviews;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final ActivityLog activity;
    private final DesignRepository designs;

    EstimateConsumers(JobQueue jobs, EstimateRepository estimates, ReviewRepository reviews, AuditWriter audit,
                      JdbcClient jdbc, ActivityLog activity, DesignRepository designs) {
        this.designs = designs;
        this.jobs = jobs;
        this.estimates = estimates;
        this.reviews = reviews;
        this.audit = audit;
        this.jdbc = jdbc;
        this.activity = activity;
    }

    @Override
    public String name() {
        return "estimation";
    }

    @Override
    public boolean accepts(String eventType) {
        return EventTypes.ESTIMATE_REQUESTED.equals(eventType) || EventTypes.TDD_APPROVED.equals(eventType)
                || EventTypes.JOB_DEAD.equals(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        if (EventTypes.ESTIMATE_REQUESTED.equals(event.eventType())) {
            UUID versionId = event.uuid("estimateVersionId");
            String attempt = String.valueOf(event.payload().get("attempt"));
            jobs.enqueue(new JobRequest(EstimateGenerationHandler.JOB_TYPE, "estimate:" + versionId + ":" + attempt,
                    "REQUIREMENT", event.aggregateId(), null, Map.of("estimateVersionId", versionId.toString()), 100));
            activity.step(event.aggregateId(), ActivityLog.Stage.ESTIMATE, versionId,
                    "Queued for the Estimation agent; it starts as soon as a worker is free.");
            return;
        }
        if (EventTypes.TDD_APPROVED.equals(event.eventType())) {
            // GD-33: hand the approved design to the project's Estimators, or tell its Organisation Admins there are none.
            UUID projectId = designs.projectOf(event.aggregateId());
            List<UUID> estimatorIds = estimates.membersWithRole(projectId, "ESTIMATOR");
            List<UUID> recipients = estimatorIds.isEmpty() ? designs.organisationAdmins() : estimatorIds;
            for (UUID user : recipients) {
                reviews.notify(event.tenantId(), user, estimatorIds.isEmpty() ? "ESTIMATE_NO_ESTIMATOR"
                        : "ESTIMATE_HANDOVER", event.aggregateId());
            }
            return;
        }
        if (!EstimateGenerationHandler.JOB_TYPE.equals(event.payload().get("jobType"))) {
            return;
        }
        String code = jdbc.sql("SELECT last_error_code FROM app.job WHERE id = :id").param("id", event.uuid("jobId"))
                .query(String.class).optional().filter(c -> c.matches("^[A-Z][A-Z0-9_]{1,63}$"))
                .orElse("ESTIMATE_FAILED");
        estimates.open(event.aggregateId(), true).filter(v -> "GENERATING".equals(v.status())).ifPresent(v -> {
            estimates.fail(v.id(), code);
            activity.fail(v.requirementId(), ActivityLog.Stage.ESTIMATE, v.id(), "The estimate stopped after its "
                    + "automatic retries. An Estimator can try again from this page.");
            reviews.notify(v.tenantId(), v.requestedBy(), "ESTIMATE_FAILED", v.requirementId());
            audit.record(AuditAction.ESTIMATE_FAILED, "ESTIMATE_VERSION", v.id(), AuditResult.FAILED);
        });
    }
}
