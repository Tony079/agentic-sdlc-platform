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
 * GD-34 worker reactions: PlanRequested enqueues PLAN_GENERATION; EstimateApproved hands the estimate to the project's
 * Estimators for planning (Organisation Admins when there are none); a dead PLAN_GENERATION job marks the plan FAILED.
 */
@Component
@Profile("!migrate & !provision")
class PlanConsumers implements EventConsumer {

    private final JobQueue jobs;
    private final PlanRepository plans;
    private final EstimateRepository estimates;
    private final DesignRepository designs;
    private final ReviewRepository reviews;
    private final AuditWriter audit;
    private final JdbcClient jdbc;
    private final ActivityLog activity;

    PlanConsumers(JobQueue jobs, PlanRepository plans, EstimateRepository estimates, DesignRepository designs,
                  ReviewRepository reviews, AuditWriter audit, JdbcClient jdbc, ActivityLog activity) {
        this.jobs = jobs;
        this.plans = plans;
        this.estimates = estimates;
        this.designs = designs;
        this.reviews = reviews;
        this.audit = audit;
        this.jdbc = jdbc;
        this.activity = activity;
    }

    @Override
    public String name() {
        return "delivery-plan";
    }

    @Override
    public boolean accepts(String eventType) {
        return EventTypes.PLAN_REQUESTED.equals(eventType) || EventTypes.ESTIMATE_APPROVED.equals(eventType)
                || EventTypes.JOB_DEAD.equals(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        if (EventTypes.PLAN_REQUESTED.equals(event.eventType())) {
            UUID versionId = event.uuid("planVersionId");
            String attempt = String.valueOf(event.payload().get("attempt"));
            jobs.enqueue(new JobRequest(PlanGenerationHandler.JOB_TYPE, "plan:" + versionId + ":" + attempt,
                    "REQUIREMENT", event.aggregateId(), null, Map.of("planVersionId", versionId.toString()), 100));
            activity.step(event.aggregateId(), ActivityLog.Stage.PLAN, versionId,
                    "Queued for the Planning agent; it starts as soon as a worker is free.");
            return;
        }
        if (EventTypes.ESTIMATE_APPROVED.equals(event.eventType())) {
            UUID projectId = designs.projectOf(event.aggregateId());
            List<UUID> estimatorIds = estimates.membersWithRole(projectId, "ESTIMATOR");
            for (UUID user : estimatorIds.isEmpty() ? designs.organisationAdmins() : estimatorIds) {
                reviews.notify(event.tenantId(), user, estimatorIds.isEmpty() ? "PLAN_NO_ESTIMATOR" : "PLAN_HANDOVER",
                        event.aggregateId());
            }
            return;
        }
        if (!PlanGenerationHandler.JOB_TYPE.equals(event.payload().get("jobType"))) {
            return;
        }
        String code = jdbc.sql("SELECT last_error_code FROM app.job WHERE id = :id").param("id", event.uuid("jobId"))
                .query(String.class).optional().filter(c -> c.matches("^[A-Z][A-Z0-9_]{1,63}$"))
                .orElse("PLAN_FAILED");
        plans.open(event.aggregateId(), true).filter(v -> "GENERATING".equals(v.status())).ifPresent(v -> {
            plans.fail(v.id(), code);
            activity.fail(v.requirementId(), ActivityLog.Stage.PLAN, v.id(), "The delivery plan stopped after its "
                    + "automatic retries. An Estimator can try again from this page.");
            reviews.notify(v.tenantId(), v.requestedBy(), "PLAN_FAILED", v.requirementId());
            audit.record(AuditAction.PLAN_FAILED, "PLAN_VERSION", v.id(), AuditResult.FAILED);
        });
    }
}
