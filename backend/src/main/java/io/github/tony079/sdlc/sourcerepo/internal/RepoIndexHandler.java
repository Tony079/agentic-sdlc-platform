package io.github.tony079.sdlc.sourcerepo.internal;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.sourcerepo.internal.BuildFileParser.Dependency;
import io.github.tony079.sdlc.sourcerepo.internal.JavaSymbolExtractor.Symbol;
import io.github.tony079.sdlc.sourcerepo.internal.SnapshotArchive.SnapshotRejected;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.SnapshotRow;
import io.github.tony079.sdlc.storage.api.ObjectStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REPO_INDEX (TDD 14.6): streams the stored snapshot again and records Java symbols (JavaParser, per-file time limit,
 * files of at most 1 MB) and Maven/Gradle dependency coordinates. Static analysis only. Residual risk (D-M4R-07):
 * JavaParser runs inside the worker JVM; a parse that exceeds its time limit is abandoned, not killed.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class RepoIndexHandler implements JobHandler<RepoIndexHandler.Outcome> {

    static final int MAX_SYMBOLS = 200_000;

    record Outcome(UUID snapshotId, List<Symbol> symbols, List<Dependency> dependencies, int timedOut) {
    }

    private final WorkerRepository repositories;
    private final ObjectStore storage;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final Duration parseTimeout;

    RepoIndexHandler(WorkerRepository repositories, ObjectStore storage, AuditWriter audit,
                     PlatformTransactionManager transactionManager,
                     @Value("${sdlc.repository.index.parse-timeout:10s}") Duration parseTimeout) {
        this.repositories = repositories;
        this.storage = storage;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setReadOnly(true);
        this.parseTimeout = parseTimeout;
    }

    @Override
    public String jobType() {
        return RepoJobs.INDEX;
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID snapshotId = RepoJobs.uuid(job, "snapshotId");
        SnapshotRow snapshot = tx.execute(status -> repositories.snapshot(snapshotId, false).orElse(null));
        if (snapshot == null) {
            throw JobFailure.fatal("SNAPSHOT_NOT_FOUND", null);
        }
        if (!"STORED".equals(snapshot.status())) {
            return new Outcome(snapshotId, null, null, 0);
        }
        List<Symbol> symbols = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();
        int[] timedOut = {0};
        ExecutorService[] parser = {newParser()};
        try (InputStream in = storage.open(snapshot.storageKey())) {
            SnapshotArchive.read(in, snapshot.archiveBytes(), snapshot.commitSha(), (path, language, content) -> {
                switch (language) {
                    case "JAVA" -> {
                        if (symbols.size() >= MAX_SYMBOLS) {
                            return;
                        }
                        Future<List<Symbol>> parsed = parser[0].submit(() -> JavaSymbolExtractor.extract(path, content));
                        try {
                            List<Symbol> found = parsed.get(parseTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                            symbols.addAll(found.subList(0, Math.min(found.size(), MAX_SYMBOLS - symbols.size())));
                        } catch (TimeoutException e) {
                            parsed.cancel(true);
                            parser[0].shutdownNow();
                            parser[0] = newParser();
                            timedOut[0]++;
                        } catch (ExecutionException e) {
                            // Unparseable file: no symbols.
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted");
                        }
                    }
                    case "MAVEN" -> dependencies.addAll(BuildFileParser.maven(path, content));
                    case "GRADLE" -> dependencies.addAll(BuildFileParser.gradle(path, content));
                    default -> { }
                }
            });
        } catch (SnapshotRejected e) {
            throw JobFailure.fatal(e.code(), null);
        } catch (IOException e) {
            throw JobFailure.retryable("SNAPSHOT_STORAGE_FAILED", e);
        } finally {
            parser[0].shutdownNow();
        }
        return new Outcome(snapshotId, symbols, dependencies, timedOut[0]);
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        if (outcome.symbols() == null) {
            return;
        }
        SnapshotRow snapshot = repositories.snapshot(outcome.snapshotId(), true).orElseThrow();
        if (!"STORED".equals(snapshot.status())) {
            return;
        }
        repositories.insertSymbols(job.tenantId(), outcome.snapshotId(), outcome.symbols());
        repositories.insertDependencies(job.tenantId(), outcome.snapshotId(), outcome.dependencies());
        repositories.markIndexed(outcome.snapshotId());
        audit.record(AuditAction.SNAPSHOT_INDEXED, "REPOSITORY_SNAPSHOT", outcome.snapshotId(), AuditResult.SUCCESS);
    }

    private static ExecutorService newParser() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "repo-index-parser");
            thread.setDaemon(true);
            return thread;
        });
    }
}
