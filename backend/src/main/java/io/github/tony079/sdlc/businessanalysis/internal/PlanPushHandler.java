package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.sourcerepo.api.IssueTracker;
import io.github.tony079.sdlc.sourcerepo.api.IssueTracker.Ref;
import io.github.tony079.sdlc.sourcerepo.api.IssueTracker.TrackerException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PLAN_PUSH (GD-34 phase 2): pushes the frozen content of a plan push to GitHub Issues: labels, one milestone per
 * sprint, then one issue per story in plan order (so a story's dependencies usually have issue numbers already).
 *
 * <p>Creating an issue is an effect outside Garuda, so each item is recorded in its own transaction as soon as GitHub
 * returns it. A later attempt of the same push skips what it already pushed; a later push updates the recorded
 * milestones and issues instead of creating new ones. Commit closes the push (DONE, PARTIAL or FAILED) and tells the
 * Estimator who asked.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class PlanPushHandler implements JobHandler<PlanPushHandler.Outcome> {

    static final String JOB_TYPE = "PLAN_PUSH";
    static final Duration NOT_CONFIGURED_DELAY = Duration.ofMinutes(10);

    record Outcome(UUID pushId, int failed, String failureCode) {
    }

    private final IssueTracker tracker;
    private final PlanPushRepository pushes;
    private final ReviewRepository reviews;
    private final AuditWriter audit;
    private final ActivityLog activity;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    PlanPushHandler(IssueTracker tracker, PlanPushRepository pushes, ReviewRepository reviews, AuditWriter audit,
                    ActivityLog activity, JsonMapper json, PlatformTransactionManager transactionManager) {
        this.tracker = tracker;
        this.pushes = pushes;
        this.reviews = reviews;
        this.audit = audit;
        this.activity = activity;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID pushId = BaAnalysisHandler.uuid(job, "planPushId");
        PlanPushRepository.PushRow push = tx.execute(s -> {
            pushes.markRunning(pushId);
            return pushes.find(pushId, false).orElse(null);
        });
        if (push == null || !"RUNNING".equals(push.status())) {
            return null;
        }
        PlanIssues.Payload payload = json.readValue(push.payloadJson(), PlanIssues.Payload.class);
        UUID requirementId = push.requirementId();
        activity.step(requirementId, ActivityLog.Stage.PLAN, push.planVersionId(), "Pushing plan version "
                + push.planVersionNo() + " to GitHub repository " + push.repository() + ": "
                + ActivityLog.count(payload.milestones().size(), "sprint milestone") + " and "
                + ActivityLog.count(payload.issues().size(), "issue") + (job.attemptNo() > 1
                ? " (attempt " + job.attemptNo() + ", continuing where it stopped)" : "") + ".");
        Map<String, Integer> milestones = new HashMap<>();
        Map<String, Integer> issues = new HashMap<>();
        Map<String, Boolean> doneInThisPush = new HashMap<>();
        tx.executeWithoutResult(s -> {
            for (PlanPushRepository.ItemRow i : pushes.items(requirementId, push.repositoryId())) {
                (("MILESTONE".equals(i.kind())) ? milestones : issues).put(i.itemKey(), i.number());
                if (pushId.equals(i.lastPushId())) {
                    doneInThisPush.put(i.kind() + ":" + i.itemKey(), true);
                }
            }
        });
        int failed = 0;
        try {
            IssueTracker.Session session = tx.execute(s -> {
                try {
                    return tracker.open(push.repositoryId());
                } catch (TrackerException e) {
                    throw new Wrapped(e);
                }
            });
            for (PlanIssues.Label label : payload.labels()) {
                session.label(label.name(), label.color(), label.description());
            }
            for (PlanIssues.Milestone m : payload.milestones()) {
                if (doneInThisPush.containsKey("MILESTONE:" + m.key())) {
                    continue;
                }
                try {
                    Ref ref = session.milestone(milestones.get(m.key()), m.title(), m.description(),
                            LocalDate.parse(m.dueOn()));
                    milestones.put(m.key(), ref.number());
                    tx.executeWithoutResult(s -> pushes.record(push, "MILESTONE", m.key(), ref.number(), ref.url(),
                            ref.created()));
                } catch (TrackerException e) {
                    if (e.kind() != TrackerException.Kind.ITEM_REJECTED) {
                        throw e;
                    }
                    failed++;
                }
            }
            for (PlanIssues.Issue issue : payload.issues()) {
                if (doneInThisPush.containsKey("ISSUE:" + issue.key())) {
                    continue;
                }
                try {
                    Integer existing = issues.get(issue.key());
                    String current = existing == null ? null : session.body(existing);
                    if (current == null) {
                        existing = null;
                    }
                    Ref ref = session.issue(existing, PlanIssues.title(issue),
                            PlanIssues.body(payload, issue, issues, current), issue.labels(),
                            issue.milestone() == null ? null : milestones.get(issue.milestone()));
                    issues.put(issue.key(), ref.number());
                    tx.executeWithoutResult(s -> pushes.record(push, "ISSUE", issue.key(), ref.number(), ref.url(),
                            ref.created()));
                } catch (TrackerException e) {
                    if (e.kind() != TrackerException.Kind.ITEM_REJECTED) {
                        throw e;
                    }
                    failed++;
                }
            }
        } catch (Wrapped w) {
            return failure(pushId, w.cause, failed);
        } catch (TrackerException e) {
            return failure(pushId, e, failed);
        }
        return new Outcome(pushId, failed, failed > 0 ? "GITHUB_ITEMS_REJECTED" : null);
    }

    /** Rate limits and outages wait and continue; a missing permission or repository ends the push. */
    private Outcome failure(UUID pushId, TrackerException e, int failed) throws JobFailure {
        return switch (e.kind()) {
            case RETRY -> throw e.delay() != null ? JobFailure.dependencyUnavailable(e.code(), e.delay())
                    : JobFailure.retryable(e.code(), null);
            case NOT_CONFIGURED -> throw JobFailure.dependencyUnavailable(e.code(), NOT_CONFIGURED_DELAY);
            default -> new Outcome(pushId, failed, e.code());
        };
    }

    /** Carries a checked failure out of a transaction callback. */
    private static final class Wrapped extends RuntimeException {
        private final TrackerException cause;

        Wrapped(TrackerException cause) {
            super(cause.code(), null, false, false);
            this.cause = cause;
        }
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        if (outcome == null) {
            return;
        }
        PlanPushRepository.PushRow push = pushes.find(outcome.pushId(), true).orElse(null);
        if (push == null || !"RUNNING".equals(push.status())) {
            return;
        }
        int pushed = push.created() + push.updated();
        String status = outcome.failureCode() == null ? "DONE" : pushed > 0 ? "PARTIAL" : "FAILED";
        pushes.finish(push.id(), status, outcome.failed(), outcome.failureCode());
        String summary = "created " + push.created() + ", updated " + push.updated()
                + (outcome.failed() > 0 ? ", " + outcome.failed() + " refused by GitHub" : "");
        switch (status) {
            case "DONE" -> {
                audit.record(AuditAction.PLAN_PUSHED, "PLAN_PUSH", push.id(), AuditResult.SUCCESS);
                activity.step(push.requirementId(), ActivityLog.Stage.PLAN, push.planVersionId(),
                        "Pushed to GitHub repository " + push.repository() + ": " + summary + ".");
                reviews.notify(push.tenantId(), push.requestedBy(), "PLAN_PUSHED", push.requirementId());
            }
            default -> {
                audit.record(AuditAction.PLAN_PUSH_FAILED, "PLAN_PUSH", push.id(), AuditResult.FAILED);
                activity.step(push.requirementId(), ActivityLog.Stage.PLAN, push.planVersionId(),
                        ("PARTIAL".equals(status) ? "Pushed part of the plan to " : "Could not push the plan to ")
                                + push.repository() + " (" + PlanService.pushReason(outcome.failureCode()) + "): "
                                + summary + ".");
                reviews.notify(push.tenantId(), push.requestedBy(), "PLAN_PUSH_FAILED", push.requirementId());
            }
        }
    }
}
