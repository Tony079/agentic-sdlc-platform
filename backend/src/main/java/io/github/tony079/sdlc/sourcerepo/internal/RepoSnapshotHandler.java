package io.github.tony079.sdlc.sourcerepo.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;
import io.github.tony079.sdlc.sourcerepo.internal.SnapshotArchive.Inventory;
import io.github.tony079.sdlc.sourcerepo.internal.SnapshotArchive.SnapshotRejected;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.RepositoryRow;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.ValidationRow;
import io.github.tony079.sdlc.storage.api.ObjectStore;
import io.github.tony079.sdlc.storage.api.ObjectStore.StoredObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REPO_SNAPSHOT (TDD 14.5): downloads the tarball of the pinned commit through the broker, stores it encrypted in the
 * object store (at most 200 MB), then streams it once to build the file inventory under the entry, size and ratio
 * limits. A limit or integrity violation records a FAILED snapshot (no retry: it would fail the same way). Nothing is
 * extracted to disk. Retention is open (O-25): snapshots are never deleted by the platform yet.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class RepoSnapshotHandler implements JobHandler<RepoSnapshotHandler.Outcome> {

    static final long MAX_ARCHIVE_BYTES = 200L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(RepoSnapshotHandler.class);

    /** stored == null with failureCode set: rejected; both null: nothing to do. */
    record Outcome(UUID requirementRepositoryId, UUID repositoryId, String sha, StoredObject stored,
                   Inventory inventory, String failureCode) {
    }

    private final RepositoryAccess broker;
    private final WorkerRepository repositories;
    private final ObjectStore storage;
    private final JobQueue jobs;
    private final AuditWriter audit;
    private final TransactionTemplate tx;

    RepoSnapshotHandler(RepositoryAccess broker, WorkerRepository repositories, ObjectStore storage, JobQueue jobs,
                        AuditWriter audit, PlatformTransactionManager transactionManager) {
        this.broker = broker;
        this.repositories = repositories;
        this.storage = storage;
        this.jobs = jobs;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setReadOnly(true);
    }

    @Override
    public String jobType() {
        return RepoJobs.SNAPSHOT;
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID rrId = RepoJobs.uuid(job, "requirementRepositoryId");
        ValidationRow row = tx.execute(status -> repositories.validationById(rrId).orElse(null));
        if (row == null || !"VALIDATED".equals(row.status())) {
            throw JobFailure.fatal("SNAPSHOT_NOT_VALIDATED", null);
        }
        if (Boolean.TRUE.equals(tx.execute(status -> repositories.snapshotFor(rrId).isPresent()))) {
            return new Outcome(rrId, row.repository().id(), row.pinnedSha(), null, null, null);
        }
        RepositoryRow repo = row.repository();
        StoredObject stored;
        try {
            RepositoryAccess.Session session = broker.open(repo.owner(), repo.name());
            try (InputStream archive = session.tarball(row.pinnedSha())) {
                stored = storage.put(job.tenantId(), archive, MAX_ARCHIVE_BYTES);
            }
        } catch (GitHubException e) {
            throw RepoJobs.failure(e);
        } catch (ObjectStore.TooLargeException e) {
            return new Outcome(rrId, repo.id(), row.pinnedSha(), null, null, "SNAPSHOT_ARCHIVE_TOO_LARGE");
        } catch (IOException e) {
            throw JobFailure.retryable("SNAPSHOT_DOWNLOAD_FAILED", e);
        }
        try (InputStream in = storage.open(stored.key())) {
            Inventory inventory = SnapshotArchive.read(in, stored.size(), row.pinnedSha(), null);
            return new Outcome(rrId, repo.id(), row.pinnedSha(), stored, inventory, null);
        } catch (SnapshotRejected e) {
            deleteQuietly(stored.key());
            return new Outcome(rrId, repo.id(), row.pinnedSha(), null, null, e.code());
        } catch (IOException e) {
            deleteQuietly(stored.key());
            throw JobFailure.retryable("SNAPSHOT_STORAGE_FAILED", e);
        }
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        if (repositories.snapshotFor(outcome.requirementRepositoryId()).isPresent()) {
            if (outcome.stored() != null) {
                deleteQuietly(outcome.stored().key());
            }
            return;
        }
        if (outcome.stored() == null && outcome.failureCode() == null) {
            return;
        }
        if (outcome.failureCode() != null) {
            repositories.insertFailedSnapshot(job.tenantId(), outcome.requirementRepositoryId(), outcome.repositoryId(),
                    outcome.sha(), outcome.failureCode());
            audit.record(AuditAction.SNAPSHOT_FAILED, "REQUIREMENT_REPOSITORY", outcome.requirementRepositoryId(),
                    AuditResult.FAILED);
            return;
        }
        Inventory inventory = outcome.inventory();
        UUID snapshotId = repositories.insertSnapshot(job.tenantId(), outcome.requirementRepositoryId(),
                outcome.repositoryId(), outcome.sha(), outcome.stored().key(), outcome.stored().sha256(),
                outcome.stored().size(), inventory.files().size(), inventory.skippedCount(), inventory.expandedBytes());
        repositories.insertFiles(job.tenantId(), snapshotId, inventory.files());
        audit.record(AuditAction.SNAPSHOT_CREATED, "REPOSITORY_SNAPSHOT", snapshotId, AuditResult.SUCCESS);
        jobs.enqueue(new JobRequest(RepoJobs.INDEX, "repo-index:" + snapshotId, "REQUIREMENT", job.aggregateId(), null,
                Map.of("snapshotId", snapshotId.toString()), 100));
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Could not delete an unused snapshot object");
        }
    }
}
