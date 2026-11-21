package io.github.tony079.sdlc.businessanalysis.internal;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.modelgateway.api.DataBlock;
import io.github.tony079.sdlc.modelgateway.api.ModelException;
import io.github.tony079.sdlc.modelgateway.api.ModelGateway;
import io.github.tony079.sdlc.modelgateway.api.ModelRequest;
import io.github.tony079.sdlc.modelgateway.api.ModelResponse;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.sandbox.api.Devbox;
import io.github.tony079.sdlc.sourcerepo.api.CodeHost;
import io.github.tony079.sdlc.sourcerepo.api.IssueTracker.TrackerException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DEV_STORY (GD-36): Garuda's Developer agent codes one story. The repository at the default branch's head goes into a
 * fresh devbox workspace; the model works in sessions of at most 10 tool rounds (fresh context each time, with the work
 * log of earlier sessions and the files changed so far) until it is done, gives up or the session budget is spent.
 * Garuda then checks the change (not empty, within limits, no CI configuration) and commits it to the story's garuda/
 * branch with a pull request that closes the story's issue. People review and merge in GitHub.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class DevStoryHandler implements JobHandler<DevStoryHandler.Outcome> {

    static final String JOB_TYPE = "DEV_STORY";
    static final int MAX_SESSIONS = 8;
    static final int MAX_FILES = 200;
    /** A pull request branch in the same repository can run workflows with the repository's secrets. */
    static final List<String> FORBIDDEN_PREFIXES = List.of(".github/workflows/", ".github/actions/", ".git/");

    record Outcome(UUID taskId, boolean ok, String failureCode, String summary, String branch, String baseSha,
                   String headSha, int prNumber, String prUrl, Boolean testsPassed, int files, String workLog,
                   boolean draft) {

        static Outcome failed(UUID taskId, String code, String summary) {
            return new Outcome(taskId, false, code, summary, null, null, null, 0, null, null, 0, null, false);
        }
    }

    /** The longest work log kept between attempts (the newest part). */
    static final int MAX_WORK_LOG = 20_000;

    private record Inputs(DevRepository.TaskRow task, DevRepository.RunRow run, String requirementKey,
                          PlanIssues.Issue story, Integer issueNumber, String design) {
    }

    private final ModelGateway gateway;
    private final Devbox devbox;
    private final CodeHost codeHost;
    private final DevRepository devs;
    private final PlanPushRepository pushes;
    private final PlanRepository plans;
    private final EstimateRepository estimates;
    private final DesignRepository designs;
    private final ReviewRepository reviews;
    private final ActivityLog activity;
    private final AuditWriter audit;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    DevStoryHandler(ModelGateway gateway, Devbox devbox, CodeHost codeHost, DevRepository devs,
                    PlanPushRepository pushes, PlanRepository plans, EstimateRepository estimates,
                    DesignRepository designs, ReviewRepository reviews, ActivityLog activity, AuditWriter audit,
                    JsonMapper json, PlatformTransactionManager transactionManager) {
        this.gateway = gateway;
        this.devbox = devbox;
        this.codeHost = codeHost;
        this.devs = devs;
        this.pushes = pushes;
        this.plans = plans;
        this.estimates = estimates;
        this.designs = designs;
        this.reviews = reviews;
        this.activity = activity;
        this.audit = audit;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID taskId = BaAnalysisHandler.uuid(job, "devTaskId");
        Inputs in = tx.execute(s -> load(taskId));
        if (in == null) {
            return null;
        }
        if (!devbox.enabled()) {
            throw JobFailure.dependencyUnavailable("DEVBOX_NOT_CONFIGURED", java.time.Duration.ofMinutes(10));
        }
        tx.executeWithoutResult(s -> devs.startCoding(taskId));
        UUID requirementId = in.run().requirementId();
        ActivityLog.Stage stage = ActivityLog.Stage.DEVELOPMENT;
        activity.step(requirementId, stage, in.run().id(), "Coding \"" + in.task().title() + "\" (wave "
                + in.task().wave() + ")" + (in.task().attempts() > 0 ? ", attempt " + (in.task().attempts() + 1) : "")
                + ": preparing a workspace from " + in.run().repository() + ".");
        String workspace = null;
        try {
            CodeHost.Session session = tx.execute(s -> {
                try {
                    return codeHost.open(in.run().repositoryId(), devs.baseBranch(requirementId,
                            in.run().repositoryId()));
                } catch (TrackerException e) {
                    throw new Wrapped(e);
                }
            });
            String baseSha = session.head();
            activity.step(requirementId, stage, in.run().id(), "\"" + in.task().title() + "\": working from "
                    + session.baseBranch() + "; its pull request goes into " + session.baseBranch() + ".");
            String branch = branch(in.requirementKey(), in.task().storyKey());
            // Unfinished work from an earlier attempt is on the story's branch (with anything a person pushed there):
            // carry on from its head and its work log.
            String resumeFrom = in.task().draft() ? session.branchHead(branch) : null;
            String startSha = resumeFrom != null ? resumeFrom : baseSha;
            try (InputStream tarball = session.tarball(startSha)) {
                workspace = devbox.create(tarball);
            }
            List<String> log = new ArrayList<>();
            if (resumeFrom != null) {
                log.add("Earlier attempt (its changes are already in this workspace; the list of changed files below "
                        + "shows only this attempt's): " + (in.task().workLog() == null ? "(no notes kept)"
                        : in.task().workLog()));
                activity.step(requirementId, stage, in.run().id(), "\"" + in.task().title() + "\": carrying on from "
                        + "the unfinished work on " + branch + (in.task().prNumber() == null ? ""
                        : " (draft pull request #" + in.task().prNumber() + ")") + ".");
            }
            DevContracts.Step last = null;
            String previousChanged = null;
            int idle = 0;
            for (int n = 1; n <= MAX_SESSIONS; n++) {
                String changed = devbox.action(workspace, "exec", Map.of("argv", List.of("git", "status", "--short"),
                        "timeoutSeconds", 60));
                List<DataBlock> data = new ArrayList<>();
                data.add(storyBlock(in));
                if (!in.design().isBlank()) {
                    data.add(new DataBlock("approved technical design items this story implements", in.design()));
                }
                data.add(new DataBlock("work log of your earlier sessions on this story", log.isEmpty()
                        ? "(this is your first session)" : String.join("\n\n", log)));
                data.add(new DataBlock("files changed so far (git status --short, as JSON)", changed));
                String title = "\"" + in.task().title() + "\"";
                activity.step(requirementId, stage, in.run().id(), title + ": session " + n + " of at most "
                        + MAX_SESSIONS + ".");
                String outcome;
                int toolCalls;
                try {
                    ModelResponse<DevContracts.Step> response = gateway.generate(new ModelRequest<>(BaPrompts.DEV_STORY,
                            data, DevContracts.Step.class, requirementId, 4096, BaAnalysisHandler.LONG_CALL_TIMEOUT,
                            DevTools.forWorkspace(devbox, workspace)));
                    last = response.output();
                    toolCalls = response.toolCalls().values().stream().mapToInt(Integer::intValue).sum();
                    outcome = last.status() + " · " + tools(response.toolCalls());
                    log.add("Session " + n + " (" + last.status() + ", " + toolCalls + " tool calls): " + last.summary()
                            + (last.testsRun().isEmpty() ? "" : " Commands: " + String.join("; ", last.testsRun()))
                            + (last.nextSteps() == null ? "" : " Next: " + last.nextSteps()));
                } catch (ModelException e) {
                    if (e.code() != ModelException.Code.INVALID_OUTPUT && e.code() != ModelException.Code.EMPTY_OUTPUT) {
                        throw e;
                    }
                    if (ModelException.TOOL_LIMIT.equals(e.detail())) {
                        // It worked but did not answer in time; the workspace keeps its changes.
                        toolCalls = 1;
                        outcome = "used all its tool rounds without answering";
                        log.add("Session " + n + ": used all 10 tool rounds without answering. Next session: write the "
                                + "change now, run the tests, and answer by round 8.");
                    } else {
                        toolCalls = 0;
                        outcome = "no usable answer" + (e.detail() == null ? "" : " (" + PlanIssues.text(e.detail(), 120)
                                + ")");
                        log.add("Session " + n + ": your answer could not be used" + (e.detail() == null ? ""
                                : " (" + e.detail() + ")") + ". Answer with the JSON object only.");
                    }
                }
                String nowChanged = devbox.action(workspace, "exec", Map.of("argv", List.of("git", "status",
                        "--short"), "timeoutSeconds", 60));
                int files = changedFiles(nowChanged);
                activity.add(requirementId, stage, in.run().id(), ActivityLog.Kind.STEP, title + ", session " + n + ": "
                        + outcome + " · " + files + " file(s) changed so far" + (last != null && last.summary() != null
                        && outcome.startsWith(last.status().name()) ? ". " + PlanIssues.text(last.summary(), 300) : "."));
                if (last != null && last.status() != DevContracts.Status.CONTINUE
                        && outcome.startsWith(last.status().name())) {
                    break;
                }
                // Two sessions in a row with no tool use and no new changes: stop instead of burning the rest.
                boolean progressed = toolCalls > 0 || !nowChanged.equals(previousChanged == null ? changed
                        : previousChanged);
                previousChanged = nowChanged;
                idle = progressed ? 0 : idle + 1;
                if (idle >= 2) {
                    return unfinished(in, session, workspace, branch, baseSha, startSha, "AGENT_STALLED", last, log);
                }
            }
            if (last == null) {
                return new Outcome(taskId, false, "AGENT_NO_ANSWER", null, null, null, null, 0, null, null, 0,
                        logText(log), false);
            }
            if (last.status() == DevContracts.Status.GIVE_UP) {
                // What it did write is kept on a draft, as when it runs out of sessions: a person can finish it.
                return unfinished(in, session, workspace, branch, baseSha, startSha, "AGENT_GAVE_UP", last, log);
            }
            if (last.status() == DevContracts.Status.CONTINUE) {
                return unfinished(in, session, workspace, branch, baseSha, startSha, "AGENT_OUT_OF_SESSIONS", last, log);
            }
            List<Devbox.ChangedFile> changes = devbox.changes(workspace);
            // Carrying on from earlier work, "done" with nothing new is fine: the branch already holds the change.
            String problem = resumeFrom != null && changes.isEmpty() ? null : check(changes);
            if (problem != null) {
                return new Outcome(taskId, false, problem, last.summary(), null, null, null, 0, null, null, 0,
                        logText(log), false);
            }
            String title = in.requirementKey() + ": " + PlanIssues.text(in.task().title(), 200);
            CodeHost.PullRequest pr = session.publish(branch, startSha, files(changes), title + "\n\nGenerated by the "
                    + "Garuda Developer agent.", title, pullBody(in, last, changes.size()), false);
            return new Outcome(taskId, true, null, last.summary(), branch, baseSha, pr.headSha(), pr.number(), pr.url(),
                    last.testsPassed(), changes.size(), null, false);
        } catch (Wrapped w) {
            return trackerFailure(taskId, w.cause);
        } catch (TrackerException e) {
            return trackerFailure(taskId, e);
        } catch (Devbox.DevboxException e) {
            if (e.retryable()) {
                throw JobFailure.retryable(e.code(), null);
            }
            return Outcome.failed(taskId, e.code(), null);
        } catch (ModelException e) {
            activity.add(requirementId, stage, in.run().id(), ActivityLog.Kind.FAIL, BaAnalysisHandler.describe(e));
            throw BaAnalysisHandler.failure(e);
        } catch (java.io.IOException e) {
            throw JobFailure.retryable("REPOSITORY_DOWNLOAD_FAILED", null);
        } finally {
            if (workspace != null) {
                devbox.delete(workspace);
            }
        }
    }

    private Outcome trackerFailure(UUID taskId, TrackerException e) throws JobFailure {
        return switch (e.kind()) {
            case RETRY -> throw e.delay() != null ? JobFailure.dependencyUnavailable(e.code(), e.delay())
                    : JobFailure.retryable(e.code(), null);
            case NOT_CONFIGURED -> throw JobFailure.dependencyUnavailable(e.code(), java.time.Duration.ofMinutes(10));
            default -> Outcome.failed(taskId, e.code(), null);
        };
    }

    /**
     * The agent stopped before finishing: its changes (if Garuda's checks allow them) go to the story's branch as a
     * draft pull request, and its work log is kept, so a retry carries on instead of starting again.
     */
    private Outcome unfinished(Inputs in, CodeHost.Session session, String workspace, String branch, String baseSha,
                               String startSha, String code, DevContracts.Step last, List<String> log)
            throws TrackerException, Devbox.DevboxException {
        String summary = last == null ? null : last.summary() + ("AGENT_GAVE_UP".equals(code)
                && last.nextSteps() != null ? " " + last.nextSteps() : "");
        String logText = logText(log);
        List<Devbox.ChangedFile> changes = devbox.changes(workspace);
        if (check(changes) != null) {
            // Nothing new, or nothing Garuda may publish: any earlier draft stays as it is.
            return new Outcome(in.task().id(), false, code, summary, null, null, null, 0, null, null, 0, logText, false);
        }
        String title = "[Not finished] " + in.requirementKey() + ": " + PlanIssues.text(in.task().title(), 180);
        CodeHost.PullRequest pr = session.publish(branch, startSha, files(changes), title + "\n\nUnfinished work of "
                + "the Garuda Developer agent.", title, draftBody(in, code, last, log), true);
        return new Outcome(in.task().id(), false, code, summary, branch, baseSha, pr.headSha(), pr.number(), pr.url(),
                null, changes.size(), logText, true);
    }

    private static List<CodeHost.FileChange> files(List<Devbox.ChangedFile> changes) {
        return changes.stream().map(c -> new CodeHost.FileChange(c.path(),
                "deleted".equals(c.status()) ? null : c.content(), c.executable())).toList();
    }

    /** The work log as kept between attempts: the newest {@link #MAX_WORK_LOG} characters. */
    static String logText(List<String> log) {
        String text = String.join("\n\n", log);
        return text.length() <= MAX_WORK_LOG ? text : "…" + text.substring(text.length() - MAX_WORK_LOG + 1);
    }

    private String draftBody(Inputs in, String code, DevContracts.Step last, List<String> log) {
        StringBuilder out = new StringBuilder();
        if (in.issueNumber() != null) {
            out.append("Closes #").append(in.issueNumber()).append("\n\n");
        }
        out.append("**Not finished:** ").append(DevService.reason(code)).append(". This draft holds the Developer "
                + "agent's work so far. Press **Retry** on the story in Garuda to carry on from here (commits you push "
                + "to this branch are kept), or finish it yourself and mark it ready for review.\n\n");
        if (last != null) {
            out.append("### Where it stopped\n\n").append(PlanIssues.text(last.summary(), 1500)).append("\n\n");
            if (last.nextSteps() != null) {
                out.append("**Next:** ").append(PlanIssues.text(last.nextSteps(), 1000)).append("\n\n");
            }
        }
        out.append("### Work log\n\n");
        String text = logText(log);
        out.append(PlanIssues.text(text.length() > 6000 ? "…" + text.substring(text.length() - 5999) : text, 6000))
                .append("\n\n");
        out.append("### Acceptance criteria\n\n");
        for (String c : in.story().criteria()) {
            out.append("- [ ] ").append(PlanIssues.text(c, 1000)).append('\n');
        }
        out.append("\n---\n_Written by the Garuda Developer agent for ").append(in.requirementKey())
                .append(". Garuda never merges._\n");
        return out.toString();
    }

    private static final class Wrapped extends RuntimeException {
        private final TrackerException cause;

        Wrapped(TrackerException cause) {
            super(cause.code(), null, false, false);
            this.cause = cause;
        }
    }

    /** "read_file 5, write_file 2" from the gateway's counts ("read_file:ok" -> 5); refusals counted apart. */
    static String tools(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "no tool calls";
        }
        Map<String, Integer> byTool = new java.util.TreeMap<>();
        int refused = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String[] parts = e.getKey().split(":", 2);
            if (parts.length == 2 && !"ok".equals(parts[1])) {
                refused += e.getValue();
            }
            byTool.merge(parts[0], e.getValue(), Integer::sum);
        }
        StringBuilder out = new StringBuilder("tools: ");
        byTool.forEach((k, v) -> out.append(k).append(' ').append(v).append(", "));
        out.setLength(out.length() - 2);
        return out + (refused > 0 ? " (" + refused + " refused)" : "");
    }

    /** Lines of "git status --short" in the devbox's JSON answer. */
    int changedFiles(String execJson) {
        try {
            String output = json.readTree(execJson).path("output").asString("");
            return (int) output.lines().filter(l -> !l.isBlank()).count();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Garuda's checks before anything is committed. Null when the change may be published. */
    static String check(List<Devbox.ChangedFile> changes) {
        if (changes.isEmpty()) {
            return "AGENT_NO_CHANGES";
        }
        if (changes.size() > MAX_FILES) {
            return "CHANGE_TOO_LARGE";
        }
        for (Devbox.ChangedFile c : changes) {
            String path = c.path().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_PREFIXES.stream().anyMatch(path::startsWith) || path.startsWith("/") || path.contains("..")) {
                return "CHANGE_TOUCHES_CI";
            }
        }
        return null;
    }

    static String branch(String requirementKey, String storyKey) {
        return "garuda/" + requirementKey.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-") + "/" + storyKey;
    }

    private String pullBody(Inputs in, DevContracts.Step step, int files) {
        StringBuilder out = new StringBuilder();
        if (in.issueNumber() != null) {
            out.append("Closes #").append(in.issueNumber()).append("\n\n");
        }
        out.append("### What the Developer agent did\n\n").append(PlanIssues.text(step.summary(), 1500)).append("\n\n");
        out.append("**Tests:** ").append(step.testsPassed() == null ? "no tests could be run"
                : step.testsPassed() ? "passed" : "not passing").append(step.testsRun().isEmpty() ? ""
                : " (" + PlanIssues.text(String.join("; ", step.testsRun()), 400) + ")").append("\n");
        out.append("**Files changed:** ").append(files).append("\n\n");
        out.append("### Acceptance criteria\n\n");
        for (String c : in.story().criteria()) {
            out.append("- [ ] ").append(PlanIssues.text(c, 1000)).append('\n');
        }
        out.append("\n---\n_Written by the Garuda Developer agent for ").append(in.requirementKey())
                .append(". Review it like any pull request: Garuda never merges._\n");
        return out.toString();
    }

    private static DataBlock storyBlock(Inputs in) {
        PlanIssues.Issue s = in.story();
        StringBuilder text = new StringBuilder("Story: ").append(s.title()).append('\n');
        if (s.epic() != null) {
            text.append("Epic: ").append(s.epic()).append(" — ").append(s.epicGoal()).append('\n');
        }
        text.append("Role: ").append(s.role()).append("; priority: ").append(s.priority()).append('\n');
        text.append("Acceptance criteria:\n");
        s.criteria().forEach(c -> text.append("- ").append(c).append('\n'));
        if (!s.dependsOn().isEmpty()) {
            text.append("Builds on (already merged into the default branch):\n");
            s.dependsOn().forEach(d -> text.append("- ").append(d.title()).append('\n'));
        }
        return new DataBlock("the story to implement (requirement " + in.requirementKey() + ")", text.toString());
    }

    private Inputs load(UUID taskId) {
        DevRepository.TaskRow task = devs.task(taskId, false).orElse(null);
        // CODING: an earlier attempt of this job stopped (a model error, a crash); this attempt carries on.
        if (task == null || !("QUEUED".equals(task.status()) || "CODING".equals(task.status())) || task.external()) {
            return null;
        }
        DevRepository.RunRow run = devs.run(task.devRunId(), false).orElseThrow();
        if (!"RUNNING".equals(run.status())) {
            return null;
        }
        PlanPushRepository.PushRow push = pushes.latestPushed(run.requirementId(), run.repositoryId()).orElse(null);
        if (push == null) {
            return null;
        }
        PlanIssues.Payload payload = json.readValue(push.payloadJson(), PlanIssues.Payload.class);
        PlanIssues.Issue story = payload.issues().stream().filter(i -> i.key().equals(task.storyKey())).findFirst()
                .orElse(null);
        if (story == null) {
            return null;
        }
        return new Inputs(task, run, payload.requirementKey(), story, task.issueNumber(), design(run, task.storyKey()));
    }

    /** The approved design items behind the story's estimate work items (title and detail), capped. */
    private String design(DevRepository.RunRow run, String storyKey) {
        PlanRepository.VersionRow plan = plans.find(run.planVersionId(), false).orElse(null);
        if (plan == null) {
            return "";
        }
        PlanRepository.StoryRow story = plans.stories(plan.id()).stream().filter(s -> s.storyKey().equals(storyKey))
                .findFirst().orElse(null);
        EstimateRepository.VersionRow estimate = estimates.find(plan.estimateVersionId(), false).orElse(null);
        if (story == null || estimate == null) {
            return "";
        }
        Set<String> estimateRefs = new LinkedHashSet<>();
        json.readTree(story.estimateRefsJson()).forEach(n -> estimateRefs.add(n.asString()));
        Set<String> designRefs = new LinkedHashSet<>();
        for (EstimateRepository.ItemRow item : estimates.items(estimate.id())) {
            if (estimateRefs.contains(item.itemKey())) {
                JsonNode refs = json.readTree(item.designRefsJson());
                refs.forEach(n -> designRefs.add(n.asString()));
            }
        }
        Map<String, DesignRepository.ItemRow> byKey = new HashMap<>();
        designs.items(estimate.tddVersionId()).forEach(d -> byKey.put(d.itemKey(), d));
        StringBuilder text = new StringBuilder();
        for (String key : designRefs) {
            DesignRepository.ItemRow d = byKey.get(key);
            if (d != null && text.length() < 20_000) {
                text.append("- [").append(d.section()).append("] ").append(d.title()).append(": ").append(d.detail())
                        .append('\n');
            }
        }
        return text.toString();
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        if (outcome == null) {
            return;
        }
        DevRepository.TaskRow task = devs.task(outcome.taskId(), true).orElse(null);
        if (task == null || !"CODING".equals(task.status())) {
            return;
        }
        DevRepository.RunRow run = devs.run(task.devRunId(), false).orElseThrow();
        if (outcome.ok()) {
            devs.pullRequestOpened(task.id(), outcome.branch(), outcome.baseSha(), outcome.headSha(), outcome.prNumber(),
                    outcome.prUrl(), outcome.summary(), outcome.testsPassed(), outcome.files());
            audit.record(AuditAction.DEV_STORY_CODED, "DEV_TASK", task.id(), AuditResult.SUCCESS);
            activity.done(run.requirementId(), ActivityLog.Stage.DEVELOPMENT, run.id(), "\"" + task.title()
                    + "\": pull request #" + outcome.prNumber() + " opened (" + outcome.files() + " files, tests "
                    + (outcome.testsPassed() == null ? "not run" : outcome.testsPassed() ? "passing" : "not passing")
                    + "). Waiting for a review and merge in GitHub.");
            for (UUID user : new LinkedHashSet<>(estimates.membersWithRole(run.projectId(), "TECHNICAL_APPROVER"))) {
                reviews.notify(run.tenantId(), user, "DEV_PR_OPEN", run.requirementId());
            }
        } else if (outcome.draft()) {
            devs.unfinished(task.id(), outcome.failureCode(), outcome.summary(), outcome.workLog(), outcome.branch(),
                    outcome.baseSha(), outcome.headSha(), outcome.prNumber(), outcome.prUrl(), outcome.files());
            audit.record(AuditAction.DEV_STORY_FAILED, "DEV_TASK", task.id(), AuditResult.FAILED);
            activity.fail(run.requirementId(), ActivityLog.Stage.DEVELOPMENT, run.id(), "\"" + task.title()
                    + "\" is not finished (" + DevService.reason(outcome.failureCode()) + "). Its work so far is on "
                    + "draft pull request #" + outcome.prNumber() + ": a Technical Approver or Estimator can retry it "
                    + "to carry on from there, or skip it.");
            for (UUID user : new LinkedHashSet<>(estimates.membersWithRole(run.projectId(), "TECHNICAL_APPROVER"))) {
                reviews.notify(run.tenantId(), user, "DEV_STORY_FAILED", run.requirementId());
            }
        } else {
            devs.failed(task.id(), outcome.failureCode(), outcome.summary(), outcome.workLog());
            audit.record(AuditAction.DEV_STORY_FAILED, "DEV_TASK", task.id(), AuditResult.FAILED);
            activity.fail(run.requirementId(), ActivityLog.Stage.DEVELOPMENT, run.id(), "\"" + task.title()
                    + "\" was not coded (" + DevService.reason(outcome.failureCode()) + "). A Technical Approver or "
                    + "Estimator can retry it or skip it.");
            for (UUID user : new LinkedHashSet<>(estimates.membersWithRole(run.projectId(), "TECHNICAL_APPROVER"))) {
                reviews.notify(run.tenantId(), user, "DEV_STORY_FAILED", run.requirementId());
            }
        }
    }
}
