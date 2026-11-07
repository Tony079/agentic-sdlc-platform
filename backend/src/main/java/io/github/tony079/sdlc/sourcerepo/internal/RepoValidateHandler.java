package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.document.api.DocumentProcessing;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;
import io.github.tony079.sdlc.sourcerepo.internal.GitHubBroker.RepositoryInfo;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.RepositoryRow;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.ValidationRow;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REPO_VALIDATE (enqueued by T02): waits (deferral, no retry budget) until the requirement's repository is registered
 * and verified, then pins the default branch head SHA and performs T06, or T07 when an attachment needs a decision.
 * Q-B Option A: never T08, never ANALYSING, never a BA job. A repository without access fails the job (REPO_ACCESS);
 * the dead job leads to T09.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class RepoValidateHandler implements JobHandler<RepoValidateHandler.Outcome> {

    record Outcome(UUID requirementRepositoryId, String sha, String defaultBranch) {
    }

    private final GitHubBroker broker;
    private final WorkerRepository repositories;
    private final WorkflowEngine workflow;
    private final DocumentProcessing documents;
    private final JobQueue jobs;
    private final AuditWriter audit;
    private final TransactionTemplate tx;

    RepoValidateHandler(GitHubBroker broker, WorkerRepository repositories, WorkflowEngine workflow,
                        DocumentProcessing documents, JobQueue jobs, AuditWriter audit,
                        PlatformTransactionManager transactionManager) {
        this.broker = broker;
        this.repositories = repositories;
        this.workflow = workflow;
        this.documents = documents;
        this.jobs = jobs;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setReadOnly(true);
    }

    @Override
    public String jobType() {
        return RepoJobs.VALIDATE;
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID requirementId = RepoJobs.uuid(job, "requirementId");
        ValidationRow row = tx.execute(status -> repositories.validationForRequirement(requirementId).orElse(null));
        if (row == null) {
            throw JobFailure.dependencyUnavailable("REPOSITORY_NOT_REGISTERED", RepoJobs.NOT_READY_DELAY);
        }
        if ("VALIDATED".equals(row.status())) {
            return new Outcome(row.requirementRepositoryId(), row.pinnedSha(), null);
        }
        RepositoryRow repo = row.repository();
        switch (repo.status()) {
            case "PENDING_VERIFICATION" ->
                    throw JobFailure.dependencyUnavailable("REPOSITORY_NOT_VERIFIED", RepoJobs.NOT_READY_DELAY);
            case "ACCESS_DENIED" -> throw JobFailure.fatal("REPO_ACCESS", null);
            case "UNAVAILABLE" -> throw JobFailure.fatal("REPOSITORY_UNAVAILABLE", null);
            default -> { }
        }
        try {
            GitHubBroker.Token token = broker.token(broker.installation(repo.owner(), repo.name()), repo.name());
            RepositoryInfo info = broker.repository(repo.owner(), repo.name(), token);
            if (repo.githubRepoId() == null || info.id() != repo.githubRepoId()) {
                throw JobFailure.fatal("REPOSITORY_IDENTITY_CHANGED", null);
            }
            String sha = broker.branchHead(repo.owner(), repo.name(), info.defaultBranch(), token);
            return new Outcome(row.requirementRepositoryId(), sha, info.defaultBranch());
        } catch (GitHubException e) {
            throw RepoJobs.failure(e);
        }
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        UUID requirementId = job.aggregateId();
        boolean decision = documents.needsDecision(requirementId);
        if (workflow.completeRepositoryValidation(requirementId, job.stageExecutionId(), decision).isEmpty()) {
            return; // no longer REPO_VALIDATING (for example cancelled): nothing is pinned
        }
        if (outcome.defaultBranch() != null
                && repositories.pin(outcome.requirementRepositoryId(), outcome.sha(), outcome.defaultBranch())) {
            audit.record(AuditAction.REPOSITORY_SHA_PINNED, "REQUIREMENT_REPOSITORY", outcome.requirementRepositoryId(),
                    AuditResult.SUCCESS);
        }
        jobs.enqueue(new JobRequest(RepoJobs.SNAPSHOT, "repo-snapshot:" + outcome.requirementRepositoryId(),
                "REQUIREMENT", requirementId, null,
                Map.of("requirementRepositoryId", outcome.requirementRepositoryId().toString()), 100));
    }
}
