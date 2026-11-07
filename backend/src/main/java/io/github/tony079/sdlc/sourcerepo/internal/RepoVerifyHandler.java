package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.sourcerepo.internal.GitHubBroker.RepositoryInfo;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.RepositoryRow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REPO_VERIFY (D-M4R-01): the broker checks that the GitHub App is installed on the repository and reads its id and
 * default branch from GitHub. Outcomes: VERIFIED, ACCESS_DENIED (App not installed, 403/404) or UNAVAILABLE (moved,
 * identity changed, or the GitHub repository is already registered by another tenant: reported generically). The
 * requirement's REPO_VALIDATE job is then woken.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class RepoVerifyHandler implements JobHandler<RepoVerifyHandler.Outcome> {

    record Outcome(String status, String reason, long githubRepoId, String defaultBranch) {
    }

    private final GitHubBroker broker;
    private final WorkerRepository repositories;
    private final JobQueue jobs;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final TransactionTemplate nested;

    RepoVerifyHandler(GitHubBroker broker, WorkerRepository repositories, JobQueue jobs, AuditWriter audit,
                      PlatformTransactionManager transactionManager) {
        this.broker = broker;
        this.repositories = repositories;
        this.jobs = jobs;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setReadOnly(true);
        this.nested = new TransactionTemplate(transactionManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public String jobType() {
        return RepoJobs.VERIFY;
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID repositoryId = RepoJobs.uuid(job, "repositoryId");
        RepositoryRow repo = tx.execute(status -> repositories.repository(repositoryId, false).orElse(null));
        if (repo == null) {
            throw JobFailure.fatal("REPOSITORY_NOT_FOUND", null);
        }
        try {
            GitHubBroker.Token token = broker.token(broker.installation(repo.owner(), repo.name()), repo.name());
            RepositoryInfo info = broker.repository(repo.owner(), repo.name(), token);
            if (!info.fullName().equalsIgnoreCase(repo.owner() + "/" + repo.name())) {
                return new Outcome("UNAVAILABLE", "REPOSITORY_UNAVAILABLE", 0, null);
            }
            return new Outcome("VERIFIED", null, info.id(), info.defaultBranch());
        } catch (GitHubException e) {
            return switch (e.kind()) {
                case ACCESS_DENIED -> new Outcome("ACCESS_DENIED", "REPO_ACCESS", 0, null);
                case UNAVAILABLE -> new Outcome("UNAVAILABLE", e.code(), 0, null);
                default -> throw RepoJobs.failure(e);
            };
        }
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        UUID repositoryId = job.aggregateId();
        RepositoryRow repo = repositories.repository(repositoryId, true).orElseThrow();
        String status = outcome.status();
        String reason = outcome.reason();
        if ("VERIFIED".equals(status)) {
            if (repo.githubRepoId() != null && repo.githubRepoId() != outcome.githubRepoId()) {
                status = "UNAVAILABLE";
                reason = "REPOSITORY_IDENTITY_CHANGED";
            } else {
                try {
                    nested.executeWithoutResult(s -> repositories.markVerified(repositoryId, outcome.githubRepoId(),
                            outcome.defaultBranch()));
                } catch (DuplicateKeyException e) {
                    // The GitHub repository is registered by another tenant. Nothing about it is revealed.
                    status = "UNAVAILABLE";
                    reason = "REPOSITORY_UNAVAILABLE";
                }
            }
        }
        switch (status) {
            case "VERIFIED" -> audit.record(AuditAction.REPOSITORY_VALIDATED, "REPOSITORY", repositoryId,
                    AuditResult.SUCCESS);
            case "ACCESS_DENIED" -> {
                repositories.markRepository(repositoryId, status, reason);
                audit.record(AuditAction.REPOSITORY_ACCESS_DENIED, "REPOSITORY", repositoryId, AuditResult.DENIED);
            }
            default -> {
                repositories.markRepository(repositoryId, "UNAVAILABLE", reason);
                audit.record(AuditAction.REPOSITORY_UNAVAILABLE, "REPOSITORY", repositoryId, AuditResult.FAILED);
            }
        }
        for (UUID requirementId : repositories.pendingRequirements(repositoryId)) {
            jobs.makeAvailable(RepoJobs.validateKey(requirementId));
        }
    }
}
