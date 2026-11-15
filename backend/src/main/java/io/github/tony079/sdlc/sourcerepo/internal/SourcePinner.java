package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;
import io.github.tony079.sdlc.sourcerepo.internal.GitHubBroker.RepositoryInfo;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.RepositoryRow;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.ValidationRow;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Pins the sources of a round (REPO_VALIDATE for the first round, ENV_PIN for later ones). Execute (no transaction):
 * each pending source's repository must be verified; its branch head (environment source) or default-branch head
 * (legacy linked repository) is read through the GitHub App. Commit (job transaction): pins each and enqueues its
 * snapshot.
 */
@Component
@Profile("!migrate & !provision")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class SourcePinner {

    record Pin(UUID requirementRepositoryId, String sha, String branch) {
    }

    private final GitHubBroker broker;
    private final WorkerRepository repositories;
    private final JobQueue jobs;
    private final AuditWriter audit;

    SourcePinner(GitHubBroker broker, WorkerRepository repositories, JobQueue jobs, AuditWriter audit) {
        this.broker = broker;
        this.repositories = repositories;
        this.jobs = jobs;
        this.audit = audit;
    }

    /** Head commits of the round's pending sources; validated ones are kept (null sha). */
    List<Pin> resolve(List<ValidationRow> round) throws JobFailure {
        List<Pin> pins = new ArrayList<>();
        for (ValidationRow row : round) {
            if ("VALIDATED".equals(row.status())) {
                pins.add(new Pin(row.requirementRepositoryId(), null, null));
                continue;
            }
            if ("FAILED".equals(row.status())) {
                throw JobFailure.fatal("REPOSITORY_VALIDATION_FAILED", null);
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
                String branch = row.branch() != null ? row.branch() : info.defaultBranch();
                try {
                    pins.add(new Pin(row.requirementRepositoryId(), broker.branchHead(repo.owner(), repo.name(), branch, token),
                            branch));
                } catch (GitHubException e) {
                    if (row.branch() != null && (e.kind() == GitHubException.Kind.UNAVAILABLE
                            || e.kind() == GitHubException.Kind.ACCESS_DENIED)) {
                        throw JobFailure.fatal("BRANCH_NOT_FOUND", null);
                    }
                    throw e;
                }
            } catch (GitHubException e) {
                throw RepoJobs.failure(e);
            }
        }
        return pins;
    }

    /** In the job transaction: pins new heads and enqueues one snapshot per source of the round. */
    void commit(UUID requirementId, List<Pin> pins) {
        for (Pin pin : pins) {
            if (pin.sha() != null && repositories.pin(pin.requirementRepositoryId(), pin.sha(), pin.branch())) {
                audit.record(AuditAction.REPOSITORY_SHA_PINNED, "REQUIREMENT_REPOSITORY", pin.requirementRepositoryId(),
                        AuditResult.SUCCESS);
            }
            jobs.enqueue(new JobRequest(RepoJobs.SNAPSHOT, "repo-snapshot:" + pin.requirementRepositoryId(),
                    "REQUIREMENT", requirementId, null,
                    Map.of("requirementRepositoryId", pin.requirementRepositoryId().toString()), 100));
        }
    }
}
