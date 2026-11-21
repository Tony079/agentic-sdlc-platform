package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.requirement.api.RequirementDirectory;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GD-36: the Development stage as the project sees it: the run, its waves and each story's status and pull request.
 * Technical Approvers and Estimators may pause and resume development, retry a failed story or skip one; the database
 * enforces the same.
 */
@Service
@Profile("!migrate & !provision")
class DevService {

    record TaskV(UUID id, String storyKey, String title, int wave, boolean external, Integer issueNumber, String status,
                 int attempts, String branch, Integer prNumber, String prUrl, String summary, Boolean testsPassed,
                 Integer filesChanged, String failureCode, String reason, OffsetDateTime updatedAt) {
    }

    record RunV(UUID id, String status, String repository, int currentWave, int agentSlots, OffsetDateTime startedAt,
                OffsetDateTime finishedAt, List<TaskV> tasks, Map<String, Long> counts) {
    }

    /**
     * @param canStart  no run yet, an approved plan for coding agents was pushed, and the caller controls development
     * @param notStarted why there is no run yet (null when there is one)
     */
    record DevState(UUID requirementId, boolean canControl, RunV run, boolean canStart, String notStarted) {
    }

    private final DevRepository devs;
    private final PlanPushRepository pushes;
    private final tools.jackson.databind.json.JsonMapper json;
    private final RequirementDirectory requirements;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;

    DevService(DevRepository devs, PlanPushRepository pushes, tools.jackson.databind.json.JsonMapper json,
               RequirementDirectory requirements, OutboxPublisher outbox, AuditWriter audit) {
        this.devs = devs;
        this.pushes = pushes;
        this.json = json;
        this.requirements = requirements;
        this.outbox = outbox;
        this.audit = audit;
    }

    private RequirementDirectory.VisibleRequirement visible(UUID requirementId) {
        return requirements.findVisible(requirementId).orElseThrow(ApiException::notFound);
    }

    private static boolean controls(RequirementDirectory.VisibleRequirement v) {
        return v.technicalApprover() || v.estimator();
    }

    @Transactional(readOnly = true)
    public DevState state(UUID requirementId) {
        var v = visible(requirementId);
        DevRepository.RunRow run = devs.latest(requirementId).orElse(null);
        if (run == null) {
            PlanPushRepository.PushRow push = pushes.latestPushedAny(requirementId).orElse(null);
            String why;
            boolean startable = false;
            if (push == null) {
                why = "Development starts on its own when an approved delivery plan for coding agents is pushed to "
                        + "GitHub Issues.";
            } else if (!forAgents(push)) {
                why = "The pushed plan was made for a human team (sprints), so the Developer agent does not code it. "
                        + "To use the Developer agent, start a new estimate for coding agents, re-plan, approve and push.";
            } else {
                startable = true;
                why = "The plan was pushed to " + push.repository() + " but development has not started yet (it was "
                        + "pushed before the Developer agent was available, or development was stopped).";
            }
            return new DevState(requirementId, controls(v), null, startable && controls(v), why);
        }
        List<TaskV> tasks = devs.tasks(run.id()).stream().map(t -> new TaskV(t.id(), t.storyKey(), t.title(), t.wave(),
                t.external(), t.issueNumber(), t.status(), t.attempts(), t.branch(), t.prNumber(), t.prUrl(),
                t.summary(), t.testsPassed(), t.filesChanged(), t.failureCode(),
                t.failureCode() == null ? null : reason(t.failureCode()), t.updatedAt())).toList();
        Map<String, Long> counts = new java.util.TreeMap<>();
        tasks.stream().filter(t -> !t.external()).forEach(t -> counts.merge(t.status(), 1L, Long::sum));
        return new DevState(requirementId, controls(v), new RunV(run.id(), run.status(), run.repository(),
                run.currentWave(), run.agentSlots(), run.startedAt(), run.finishedAt(), tasks, counts), false, null);
    }

    private boolean forAgents(PlanPushRepository.PushRow push) {
        PlanIssues.Payload payload = json.readValue(push.payloadJson(), PlanIssues.Payload.class);
        return payload.issues().stream().anyMatch(i -> i.agent() != null);
    }

    /** Starts development for the newest pushed plan, when it was made for coding agents and none is running. */
    @Transactional
    public DevState start(UUID requirementId) {
        var v = visible(requirementId);
        require(v);
        if (devs.latest(requirementId).isPresent()) {
            throw ApiException.conflict("DEV_RUNNING", "Development has already started for this requirement.");
        }
        PlanPushRepository.PushRow push = pushes.latestPushedAny(requirementId).filter(this::forAgents)
                .orElseThrow(() -> ApiException.conflict("DEV_NOT_STARTABLE",
                        "Push an approved delivery plan made for coding agents to GitHub first."));
        outbox.publish("REQUIREMENT", requirementId, EventTypes.DEVELOPMENT_CHANGED,
                Map.of("startPlanPushId", push.id().toString()));
        audit.record(AuditAction.DEV_CONTROLLED, "PLAN_PUSH", push.id(), AuditResult.SUCCESS);
        return state(requirementId);
    }

    @Transactional
    public DevState pause(UUID requirementId, boolean pause) {
        var v = visible(requirementId);
        require(v);
        DevRepository.RunRow run = devs.active(requirementId).orElseThrow(() ->
                ApiException.conflict("DEV_NOT_RUNNING", "Development is not running for this requirement."));
        String target = pause ? "PAUSED" : "RUNNING";
        if (!run.status().equals(target)) {
            devs.controlRun(run.id(), target, TenantContext.currentActor().orElseThrow());
            changed(requirementId, run.id());
        }
        return state(requirementId);
    }

    @Transactional
    public DevState retry(UUID requirementId, UUID taskId) {
        return control(requirementId, taskId, "QUEUED", "FAILED");
    }

    @Transactional
    public DevState skip(UUID requirementId, UUID taskId) {
        return control(requirementId, taskId, "SKIPPED", null);
    }

    private DevState control(UUID requirementId, UUID taskId, String target, String requiredStatus) {
        var v = visible(requirementId);
        require(v);
        DevRepository.RunRow run = devs.active(requirementId).orElseThrow(() ->
                ApiException.conflict("DEV_NOT_RUNNING", "Development is not running for this requirement."));
        DevRepository.TaskRow task = devs.task(taskId, true).filter(t -> t.devRunId().equals(run.id()))
                .orElseThrow(ApiException::notFound);
        boolean allowed = requiredStatus != null ? requiredStatus.equals(task.status())
                : List.of("WAITING", "FAILED", "QUEUED").contains(task.status());
        if (!allowed) {
            throw ApiException.conflict("DEV_TASK_STATE", "SKIPPED".equals(target)
                    ? "Only a story that is waiting, queued or failed can be skipped."
                    : "Only a failed story can be retried.");
        }
        devs.controlTask(task.id(), target, TenantContext.currentActor().orElseThrow());
        changed(requirementId, run.id());
        return state(requirementId);
    }

    private void changed(UUID requirementId, UUID runId) {
        outbox.publish("REQUIREMENT", requirementId, EventTypes.DEVELOPMENT_CHANGED,
                Map.of("devRunId", runId.toString()));
        audit.record(AuditAction.DEV_CONTROLLED, "DEV_RUN", runId, AuditResult.SUCCESS);
    }

    private static void require(RequirementDirectory.VisibleRequirement v) {
        if (!controls(v)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEV_CONTROL_NOT_ALLOWED",
                    "A Technical Approver or Estimator of the project controls development.");
        }
    }

    /** What a story's failure code means for a person. */
    static String reason(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case "AGENT_GAVE_UP" -> "the Developer agent could not implement it; see its summary";
            case "AGENT_OUT_OF_SESSIONS" -> "the Developer agent ran out of work sessions before finishing";
            case "AGENT_NO_ANSWER" -> "the Developer agent did not finish a session";
            case "AGENT_NO_CHANGES" -> "the Developer agent changed no files";
            case "CHANGE_TOUCHES_CI" -> "the change touched CI configuration (.github/workflows), which Garuda never "
                    + "commits";
            case "CHANGE_TOO_LARGE" -> "the change was too large for one pull request";
            case "PR_CLOSED_UNMERGED" -> "its pull request was closed without merging";
            case "GITHUB_CODE_PERMISSION" -> "the Garuda GitHub App has not been granted Contents and Pull requests: "
                    + "Read and write on this repository";
            case "REPO_ACCESS" -> "the Garuda GitHub App cannot reach this repository";
            case "MODEL_PROVIDER_ERROR" -> "the AI provider refused the request; the worker log line \"Model provider "
                    + "refused\" says why";
            case "MODEL_INVALID_OUTPUT", "MODEL_EMPTY_OUTPUT" -> "the AI model's answers could not be used";
            case "MODEL_UNAVAILABLE", "MODEL_RATE_LIMITED" -> "the AI provider was unavailable or busy";
            case "MODEL_NOT_CONFIGURED" -> "no AI model is configured";
            case "DEVBOX_NOT_CONFIGURED", "DEVBOX_UNREACHABLE" -> "the sandbox (devbox) is not running";
            case "DEVBOX_ARCHIVE_TOO_LARGE", "DEVBOX_ARCHIVE_INVALID" -> "the repository is too large or unreadable "
                    + "for the sandbox";
            default -> "an error (" + code + ")";
        };
    }
}
