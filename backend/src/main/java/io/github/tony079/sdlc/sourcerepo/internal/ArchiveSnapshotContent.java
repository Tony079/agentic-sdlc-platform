package io.github.tony079.sdlc.sourcerepo.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import io.github.tony079.sdlc.sourcerepo.api.SnapshotContent;
import io.github.tony079.sdlc.sourcerepo.internal.SnapshotArchive.SnapshotRejected;
import io.github.tony079.sdlc.storage.api.ObjectStore;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("!migrate & !provision & !retention")
class ArchiveSnapshotContent implements SnapshotContent {

    private final WorkerRepository repositories;
    private final ObjectStore storage;
    private final TransactionTemplate tx;

    ArchiveSnapshotContent(WorkerRepository repositories, ObjectStore storage, PlatformTransactionManager transactionManager) {
        this.repositories = repositories;
        this.storage = storage;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setReadOnly(true);
    }

    @Override
    public Optional<String> commitSha(UUID snapshotId) {
        return Optional.ofNullable(tx.execute(s -> repositories.snapshot(snapshotId, false)
                .filter(r -> "STORED".equals(r.status()) || "INDEXED".equals(r.status()))
                .map(WorkerRepository.SnapshotRow::commitSha).orElse(null)));
    }

    @Override
    public int forEachFile(UUID snapshotId, Predicate<String> paths, BiConsumer<String, byte[]> visitor)
            throws IOException {
        WorkerRepository.SnapshotRow snapshot = tx.execute(s -> repositories.snapshot(snapshotId, false).orElse(null));
        if (snapshot == null || snapshot.storageKey() == null
                || !("STORED".equals(snapshot.status()) || "INDEXED".equals(snapshot.status()))) {
            throw new IOException("snapshot not available");
        }
        int[] visited = {0};
        try (InputStream in = storage.open(snapshot.storageKey())) {
            SnapshotArchive.read(in, snapshot.archiveBytes(), snapshot.commitSha(), (path, language, content) -> {
                visited[0]++;
                visitor.accept(path, content);
            }, SnapshotArchive.Limits.DEFAULT, paths);
        } catch (SnapshotRejected e) {
            throw new IOException("snapshot rejected: " + e.code());
        }
        return visited[0];
    }
}
