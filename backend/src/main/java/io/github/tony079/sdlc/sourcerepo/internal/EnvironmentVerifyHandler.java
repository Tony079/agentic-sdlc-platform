package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.sourcerepo.internal.GitHubBroker.RepositoryInfo;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.RepositoryRow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ENV_VERIFY (GD-20, GD-21): the broker checks that the Garuda GitHub App is installed on a project repository, reads
 * the repository's identity, and records the head commit of every environment branch waiting for verification.
 * Outcomes per branch: VERIFIED (with the commit), BRANCH_MISSING, ACCESS_DENIED or UNAVAILABLE.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class EnvironmentVerifyHandler implements JobHandler<EnvironmentVerifyHandler.Outcome> {

    static final String JOB_TYPE = "ENV_VERIFY";

    record Branch(UUID id, String branch) {
    }

    record Result(String status, String sha, String code) {
    }

    record Outcome(String repoStatus, String repoReason, long githubRepoId, String defaultBranch,
                   Map<UUID, Result> branches) {
    }

    private final GitHubBroker broker;
    private final WorkerRepository repositories;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate nested;

    EnvironmentVerifyHandler(GitHubBroker broker, WorkerRepository repositories, JdbcClient jdbc,
                             PlatformTransactionManager transactionManager) {
        this.broker = broker;
        this.repositories = repositories;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.nested = new TransactionTemplate(transactionManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID repositoryId = RepoJobs.uuid(job, "repositoryId");
        RepositoryRow repo = readOnly.execute(s -> repositories.repository(repositoryId, false).orElse(null));
        if (repo == null) {
            throw JobFailure.fatal("REPOSITORY_NOT_FOUND", null);
        }
        // A refresh (GD-23, scheduled) re-reads every branch head so that moved branches can be shown.
        boolean refresh = "true".equals(String.valueOf(job.payload().get("refresh")));
        List<Branch> pending = readOnly.execute(s -> jdbc.sql("""
                SELECT id, branch FROM app.environment_branch WHERE repository_id = :repo AND (status <> 'VERIFIED' OR :all)
                """).param("repo", repositoryId).param("all", refresh).query((rs, n) -> new Branch(rs.getObject(1, UUID.class), rs.getString(2)))
                .list());
        Map<UUID, Result> results = new LinkedHashMap<>();
        try {
            GitHubBroker.Token token = broker.token(broker.installation(repo.owner(), repo.name()), repo.name());
            RepositoryInfo info = broker.repository(repo.owner(), repo.name(), token);
            if (!info.fullName().equalsIgnoreCase(repo.owner() + "/" + repo.name())) {
                pending.forEach(b -> results.put(b.id(), new Result("UNAVAILABLE", null, "REPOSITORY_UNAVAILABLE")));
                return new Outcome("UNAVAILABLE", "REPOSITORY_UNAVAILABLE", 0, null, results);
            }
            for (Branch b : pending) {
                try {
                    results.put(b.id(), new Result("VERIFIED", broker.branchHead(repo.owner(), repo.name(), b.branch(), token), null));
                } catch (GitHubException e) {
                    switch (e.kind()) {
                        case UNAVAILABLE, ACCESS_DENIED -> results.put(b.id(), new Result("BRANCH_MISSING", null, "BRANCH_NOT_FOUND"));
                        default -> throw RepoJobs.failure(e);
                    }
                }
            }
            return new Outcome("VERIFIED", null, info.id(), info.defaultBranch(), results);
        } catch (GitHubException e) {
            return switch (e.kind()) {
                case ACCESS_DENIED -> {
                    pending.forEach(b -> results.put(b.id(), new Result("ACCESS_DENIED", null, "GITHUB_APP_NOT_INSTALLED")));
                    yield new Outcome("ACCESS_DENIED", "REPO_ACCESS", 0, null, results);
                }
                case UNAVAILABLE -> {
                    pending.forEach(b -> results.put(b.id(), new Result("UNAVAILABLE", null, e.code())));
                    yield new Outcome("UNAVAILABLE", e.code(), 0, null, results);
                }
                default -> throw RepoJobs.failure(e);
            };
        }
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        UUID repositoryId = job.aggregateId();
        RepositoryRow repo = repositories.repository(repositoryId, true).orElseThrow();
        String status = outcome.repoStatus();
        if ("VERIFIED".equals(status) && !"VERIFIED".equals(repo.status())) {
            if (repo.githubRepoId() != null && repo.githubRepoId() != outcome.githubRepoId()) {
                status = "UNAVAILABLE";
                repositories.markRepository(repositoryId, status, "REPOSITORY_IDENTITY_CHANGED");
            } else {
                try {
                    nested.executeWithoutResult(s -> repositories.markVerified(repositoryId, outcome.githubRepoId(),
                            outcome.defaultBranch()));
                } catch (DuplicateKeyException e) {
                    status = "UNAVAILABLE";
                    repositories.markRepository(repositoryId, status, "REPOSITORY_UNAVAILABLE");
                }
            }
        } else if (!"VERIFIED".equals(status) && !"VERIFIED".equals(repo.status())) {
            repositories.markRepository(repositoryId, status, outcome.repoReason());
        }
        boolean repoOk = "VERIFIED".equals(status);
        boolean refresh = "true".equals(String.valueOf(job.payload().get("refresh")));
        for (Map.Entry<UUID, Result> e : outcome.branches().entrySet()) {
            Result r = repoOk ? e.getValue() : new Result(status.equals("ACCESS_DENIED") ? "ACCESS_DENIED" : "UNAVAILABLE", null,
                    e.getValue().code() == null ? "REPOSITORY_UNAVAILABLE" : e.getValue().code());
            jdbc.sql("""
                    UPDATE app.environment_branch SET status = :status, head_sha = :sha, failure_code = :code,
                           verified_at = CASE WHEN :status = 'VERIFIED' THEN clock_timestamp() END
                    WHERE id = :id AND (status <> 'VERIFIED' OR :all)
                    """).param("status", r.status()).param("sha", r.sha()).param("code", r.code()).param("id", e.getKey())
                    .param("all", refresh)
                    .update();
        }
    }
}
