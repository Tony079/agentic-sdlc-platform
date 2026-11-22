package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;
import io.github.tony079.sdlc.sourcerepo.api.CodeHost;
import io.github.tony079.sdlc.sourcerepo.api.IssueTracker.TrackerException;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GD-36: runs development wave by wave, on its own.
 * <ul>
 *   <li>{@link #start}: after an approved agent-delivery plan is pushed, one run per requirement with a task per story
 *       (outside work is followed through its issue, never coded).</li>
 *   <li>DEV_TICK (every two minutes for each running run, and at once after a person's control): reads the state of
 *       open pull requests and outside-work issues; the current wave is the first with stories not yet merged or
 *       skipped; once the outside work it depends on is closed, its stories are queued for the Developer agent, at most
 *       the plan's number of agents at once. When every story is merged or skipped, the requirement is delivered.</li>
 * </ul>
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class DevOrchestrator implements JobHandler<DevOrchestrator.Observed> {

    static final String JOB_TYPE = "DEV_TICK";
    private static final Logger log = LoggerFactory.getLogger(DevOrchestrator.class);

    /** What GitHub said: pull request and issue states by task. */
    record Observed(UUID runId, Map<UUID, String> pulls, Map<UUID, String> issues) {
    }

    private final DevRepository devs;
    private final PlanPushRepository pushes;
    private final CodeHost codeHost;
    private final JobQueue jobs;
    private final ActivityLog activity;
    private final AuditWriter audit;
    private final ReviewRepository reviews;
    private final EstimateRepository estimates;
    private final JsonMapper json;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final long intervalSeconds;

    /** New developments: one story at a time (SERIAL, the default) or several at once (PARALLEL). */
    private final String defaultMode;

    DevOrchestrator(DevRepository devs, PlanPushRepository pushes, CodeHost codeHost, JobQueue jobs,
                    ActivityLog activity, AuditWriter audit, ReviewRepository reviews, EstimateRepository estimates,
                    JsonMapper json, JdbcClient jdbc, PlatformTransactionManager transactionManager,
                    @Value("${sdlc.worker.development-interval:2m}") java.time.Duration interval,
                    @Value("${sdlc.dev.default-mode:SERIAL}") String defaultMode) {
        this.defaultMode = "PARALLEL".equalsIgnoreCase(defaultMode) ? "PARALLEL" : "SERIAL";
        this.devs = devs;
        this.pushes = pushes;
        this.codeHost = codeHost;
        this.jobs = jobs;
        this.activity = activity;
        this.audit = audit;
        this.reviews = reviews;
        this.estimates = estimates;
        this.json = json;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.intervalSeconds = Math.max(30, interval.toSeconds());
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    // --- start ----------------------------------------------------------------------------------------------------

    /**
     * Called in the transaction that finishes a plan push: starts development for an agent-delivery plan, unless the
     * requirement already has a run going. Returns the new run's id, or null.
     */
    UUID start(PlanPushRepository.PushRow push) {
        if (devs.active(push.requirementId()).isPresent()) {
            return null;
        }
        PlanIssues.Payload payload = json.readValue(push.payloadJson(), PlanIssues.Payload.class);
        if (payload.issues().stream().allMatch(i -> i.agent() == null)) {
            return null; // a human-team plan: no Developer agent
        }
        Map<String, Integer> numbers = new HashMap<>();
        pushes.items(push.requirementId(), push.repositoryId()).stream().filter(i -> "ISSUE".equals(i.kind()))
                .forEach(i -> numbers.put(i.itemKey(), i.number()));
        int slots = jdbc.sql("SELECT coalesce((settings->>'agentSlots')::int, 2) FROM app.plan_version WHERE id = :v")
                .param("v", push.planVersionId()).query(Integer.class).optional().orElse(2);
        UUID runId = devs.insertRun(push.tenantId(), push.projectId(), push.requirementId(), push.planVersionId(),
                push.repositoryId(), slots, push.baseBranch(), defaultMode);
        DevRepository.RunRow run = devs.run(runId, false).orElseThrow();
        for (PlanIssues.Issue issue : payload.issues()) {
            boolean external = issue.agent() != null && issue.agent().external();
            devs.insertTask(run, issue.key(), issue.title(), issue.sprint() == null ? 1 : Math.max(0, issue.sprint()),
                    external, numbers.get(issue.key()));
        }
        audit.record(AuditAction.DEV_STARTED, "DEV_RUN", runId, AuditResult.SUCCESS);
        activity.step(push.requirementId(), ActivityLog.Stage.DEVELOPMENT, runId, "Development started on "
                + push.repository() + ": the Developer agent codes wave 1 first, " + slots + " stories at a time. "
                + "Each story becomes a pull request on a garuda/ branch for a person to review and merge.");
        jobs.enqueue(tick(runId, push.requirementId(), "start"));
        return runId;
    }

    static JobRequest tick(UUID runId, UUID requirementId, String reason) {
        return new JobRequest(JOB_TYPE, "dev-tick:" + runId + ":" + reason, "REQUIREMENT", requirementId, null,
                Map.of("devRunId", runId.toString()), 150);
    }

    // --- periodic --------------------------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${sdlc.worker.development-interval:2m}", initialDelayString = "60s")
    void scheduled() {
        try {
            long bucket = Instant.now().getEpochSecond() / intervalSeconds;
            List<UUID> tenants = tx.execute(s -> jdbc.sql("SELECT id FROM app.tenant WHERE status = 'ACTIVE'")
                    .query(UUID.class).list());
            for (UUID tenant : tenants) {
                TenantContext.callAs(tenant, () -> tx.execute(s -> {
                    devs.running().forEach(run -> jobs.enqueue(tick(run[0], run[1], "t" + bucket)));
                    return null;
                }));
            }
        } catch (RuntimeException e) {
            log.error("Scheduling development ticks failed", e);
        }
    }

    // --- tick ------------------------------------------------------------------------------------------------------

    @Override
    public Observed execute(ClaimedJob job) throws JobFailure {
        UUID runId = BaAnalysisHandler.uuid(job, "devRunId");
        record Snapshot(DevRepository.RunRow run, List<DevRepository.TaskRow> tasks) {
        }
        Snapshot snap = tx.execute(s -> {
            DevRepository.RunRow run = devs.run(runId, false).orElse(null);
            return run == null || !"RUNNING".equals(run.status()) ? null : new Snapshot(run, devs.tasks(runId));
        });
        if (snap == null) {
            return null;
        }
        Map<UUID, String> pulls = new HashMap<>();
        Map<UUID, String> issues = new HashMap<>();
        boolean needGitHub = snap.tasks().stream().anyMatch(t -> "PR_OPEN".equals(t.status()) || unfinishedDraft(t)
                || (t.external() && "WAITING".equals(t.status()) && t.issueNumber() != null));
        if (!needGitHub) {
            return new Observed(runId, pulls, issues);
        }
        try {
            CodeHost.Session session = tx.execute(s -> {
                try {
                    return codeHost.open(snap.run().repositoryId());
                } catch (TrackerException e) {
                    throw new IllegalStateException(e.code());
                }
            });
            for (DevRepository.TaskRow t : snap.tasks()) {
                if (("PR_OPEN".equals(t.status()) || unfinishedDraft(t)) && t.prNumber() != null) {
                    pulls.put(t.id(), session.pullState(t.prNumber()));
                } else if (t.external() && "WAITING".equals(t.status()) && t.issueNumber() != null) {
                    String state = session.issueState(t.issueNumber());
                    issues.put(t.id(), state == null ? "CLOSED" : state);
                }
            }
        } catch (TrackerException e) {
            // GitHub is not reachable right now: carry on with what is known (restarting stuck stories, queueing the
            // wave); the next tick reads GitHub again.
            log.warn("Development tick could not read GitHub ({}); continuing without it", e.code());
        } catch (IllegalStateException e) {
            log.warn("Development tick could not open GitHub ({}); continuing without it",
                    String.valueOf(e.getMessage()).matches("^[A-Z][A-Z0-9_]{1,63}$") ? e.getMessage() : "?");
        }
        return new Observed(runId, pulls, issues);
    }

    /** A story stopped unfinished with its work on a draft pull request, which a person may finish and merge. */
    private static boolean unfinishedDraft(DevRepository.TaskRow t) {
        return "FAILED".equals(t.status()) && t.draft();
    }

    @Override
    public void commit(ClaimedJob job, Observed observed) {
        if (observed == null) {
            return;
        }
        DevRepository.RunRow run = devs.run(observed.runId(), true).orElse(null);
        if (run == null || !"RUNNING".equals(run.status())) {
            return;
        }
        UUID requirementId = run.requirementId();
        ActivityLog.Stage stage = ActivityLog.Stage.DEVELOPMENT;
        for (DevRepository.TaskRow t : devs.tasks(run.id())) {
            String pull = observed.pulls().get(t.id());
            if ("MERGED".equals(pull) && "PR_OPEN".equals(t.status())) {
                devs.setTask(t.id(), "MERGED", null);
                audit.record(AuditAction.DEV_STORY_MERGED, "DEV_TASK", t.id(), AuditResult.SUCCESS);
                activity.story(requirementId, run.id(), t.id(), ActivityLog.Kind.DONE, "\"" + t.title() + "\": pull request #" + t.prNumber()
                        + " merged.");
            } else if ("MERGED".equals(pull) && unfinishedDraft(t)) {
                // A person finished the draft on its branch and merged it in GitHub.
                devs.setTask(t.id(), "MERGED", null);
                audit.record(AuditAction.DEV_STORY_MERGED, "DEV_TASK", t.id(), AuditResult.SUCCESS);
                activity.story(requirementId, run.id(), t.id(), ActivityLog.Kind.DONE, "\"" + t.title() + "\": pull request #" + t.prNumber()
                        + " (finished in GitHub) merged.");
            } else if ("CLOSED".equals(pull) && "PR_OPEN".equals(t.status())) {
                devs.failed(t.id(), "PR_CLOSED_UNMERGED", null);
                activity.story(requirementId, run.id(), t.id(), ActivityLog.Kind.WARN, "\"" + t.title() + "\": pull request #" + t.prNumber()
                        + " was closed without merging. Retry the story or skip it.");
            }
            if ("CLOSED".equals(observed.issues().get(t.id())) && "WAITING".equals(t.status())) {
                devs.setTask(t.id(), "DONE", null);
                activity.story(requirementId, run.id(), t.id(), ActivityLog.Kind.DONE, "Outside work done: \"" + t.title() + "\".");
            }
        }
        List<DevRepository.TaskRow> tasks = devs.tasks(run.id());
        Set<String> finished = Set.of("MERGED", "SKIPPED");
        Integer wave = tasks.stream().filter(t -> !t.external() && !finished.contains(t.status()))
                .map(DevRepository.TaskRow::wave).min(Integer::compare).orElse(null);
        if (wave == null) {
            devs.setRun(run.id(), "DELIVERED", run.currentWave(), true);
            audit.record(AuditAction.DEV_DELIVERED, "DEV_RUN", run.id(), AuditResult.SUCCESS);
            activity.done(requirementId, stage, run.id(), "Every story is merged or skipped: development is complete.");
            for (UUID user : new LinkedHashSet<>(estimates.membersWithRole(run.projectId(), "TECHNICAL_APPROVER"))) {
                reviews.notify(run.tenantId(), user, "DEV_DELIVERED", requirementId);
            }
            return;
        }
        if (wave != run.currentWave()) {
            devs.setRun(run.id(), "RUNNING", wave, false);
        }
        // The wave waits for the outside work its stories depend on.
        Set<String> waitingFor = blockingOutsideWork(run, tasks, wave);
        if (!waitingFor.isEmpty()) {
            if (wave != run.currentWave()) {
                activity.wave(requirementId, run.id(), wave, ActivityLog.Kind.STEP, "Wave " + wave + " waits for outside work: "
                        + String.join("; ", waitingFor) + ". Close each one's GitHub issue, or mark it done on the Development card, when it is done.");
            }
            return;
        }
        // A story left CODING without a live job (its job ended without a result) is queued again.
        for (DevRepository.TaskRow t : tasks) {
            if (t.wave() == wave && "CODING".equals(t.status()) && !devs.liveStoryJob(t.id())) {
                devs.setTask(t.id(), "QUEUED", null);
                activity.story(requirementId, run.id(), t.id(), ActivityLog.Kind.WARN, "\"" + t.title() + "\" stopped without a result; "
                        + "the Developer agent starts it again.");
                jobs.enqueue(story(t.id(), requirementId, t.attempts()));
            }
        }
        tasks = devs.tasks(run.id());
        List<DevRepository.TaskRow> inWave = tasks.stream().filter(t -> t.wave() == wave).toList();
        boolean announced = false;
        for (DevRepository.TaskRow t : toStart(run.serial(), run.agentSlots(), inWave)) {
            if ("WAITING".equals(t.status())) {
                devs.setTask(t.id(), "QUEUED", null);
                if (!announced && wave != run.currentWave()) {
                    activity.wave(requirementId, run.id(), wave, ActivityLog.Kind.STEP, "Wave " + wave + ": the Developer agent starts"
                            + (run.serial() ? ", one story at a time." : "."));
                    announced = true;
                }
            }
            jobs.enqueue(story(t.id(), requirementId, t.attempts())); // idempotent for a story already queued
        }
    }

    /**
     * The stories of a wave to queue now. SERIAL: one at a time, and only once nothing in the wave is being coded,
     * awaiting review (pull request open) or holding unfinished work on a draft, so each story starts from code that
     * includes the ones before it; queued stories (retries) go first. PARALLEL: every queued story, plus waiting ones up
     * to the free agent slots.
     */
    static List<DevRepository.TaskRow> toStart(boolean serial, int slots, List<DevRepository.TaskRow> wave) {
        List<DevRepository.TaskRow> code = wave.stream().filter(t -> !t.external()).toList();
        List<DevRepository.TaskRow> queued = code.stream().filter(t -> "QUEUED".equals(t.status())).toList();
        List<DevRepository.TaskRow> waiting = code.stream().filter(t -> "WAITING".equals(t.status())).toList();
        if (serial) {
            boolean blocked = code.stream().anyMatch(t -> "CODING".equals(t.status()) || "PR_OPEN".equals(t.status())
                    || ("FAILED".equals(t.status()) && t.draft()));
            if (blocked) {
                return List.of();
            }
            return !queued.isEmpty() ? List.of(queued.getFirst()) : waiting.isEmpty() ? List.of()
                    : List.of(waiting.getFirst());
        }
        long busy = code.stream().filter(t -> "QUEUED".equals(t.status()) || "CODING".equals(t.status())).count();
        int free = (int) Math.max(0, slots - busy);
        List<DevRepository.TaskRow> out = new java.util.ArrayList<>(queued);
        out.addAll(waiting.subList(0, Math.min(free, waiting.size())));
        return out;
    }

    static JobRequest story(UUID taskId, UUID requirementId, int attempts) {
        return new JobRequest(DevStoryHandler.JOB_TYPE, "dev-story:" + taskId + ":" + attempts, "REQUIREMENT",
                requirementId, null, Map.of("devTaskId", taskId.toString()), 120);
    }

    /** Titles of outside work, not yet done, that stories of the wave depend on. */
    private Set<String> blockingOutsideWork(DevRepository.RunRow run, List<DevRepository.TaskRow> tasks, int wave) {
        Map<String, DevRepository.TaskRow> byKey = new HashMap<>();
        tasks.forEach(t -> byKey.put(t.storyKey(), t));
        PlanPushRepository.PushRow push = pushes.latestPushed(run.requirementId(), run.repositoryId()).orElse(null);
        if (push == null) {
            return Set.of();
        }
        PlanIssues.Payload payload = json.readValue(push.payloadJson(), PlanIssues.Payload.class);
        Set<String> waiting = new LinkedHashSet<>();
        Set<String> inWave = new HashSet<>();
        tasks.stream().filter(t -> t.wave() == wave).forEach(t -> inWave.add(t.storyKey()));
        for (PlanIssues.Issue issue : payload.issues()) {
            if (!inWave.contains(issue.key())) {
                continue;
            }
            for (PlanIssues.Dependency d : issue.dependsOn()) {
                DevRepository.TaskRow dep = d.key() == null ? null : byKey.get(d.key());
                if (dep != null && dep.external() && !Set.of("DONE", "SKIPPED").contains(dep.status())) {
                    waiting.add(dep.title());
                }
            }
        }
        return waiting;
    }
}
