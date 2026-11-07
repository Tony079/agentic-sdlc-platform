package io.github.tony079.sdlc.support;

import java.util.UUID;

import io.github.tony079.sdlc.SdlcPlatformApplication;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The real worker process (profile "worker", login sdlc_worker) against the test database, started once per test
 * JVM. Scheduling is off: tests drive dispatch and job passes explicitly, so results are deterministic. Outbox
 * retries use no backoff and three attempts so retry and dead-letter paths run quickly.
 */
public final class WorkerContext {

    private static ConfigurableApplicationContext context;

    private WorkerContext() {
    }

    public static synchronized ConfigurableApplicationContext get() {
        if (context == null) {
            TestDatabase db = TestDatabase.get();
            context = new SpringApplicationBuilder(SdlcPlatformApplication.class)
                    .profiles("worker")
                    .run("--spring.datasource.url=" + db.appUrl(),
                            "--spring.datasource.username=" + db.workerUser(),
                            "--spring.datasource.password=" + db.workerPassword(),
                            "--sdlc.worker.scheduling=false",
                            "--sdlc.worker.outbox.backoff-base=0s",
                            "--sdlc.worker.outbox.max-attempts=3",
                            "--sdlc.storage.root=" + TestStorage.ROOT,
                            "--sdlc.storage.encryption-key=" + TestStorage.KEY,
                            "--sdlc.scanner.clamd.port=" + FakeClamd.port(),
                            "--sdlc.scanner.clamd.timeout=5s",
                            "--sdlc.documents.extraction.enabled=true",
                            "--sdlc.documents.extraction.timeout=60s",
                            "--sdlc.model.provider=fake",
                            "--sdlc.model.model-id=fake-deterministic-1",
                            "--sdlc.model.retry-backoff=0s",
                            "--sdlc.model.timeout=5s",
                            "--logging.structured.format.console=");
        }
        return context;
    }

    public static <T> T bean(Class<T> type) {
        return get().getBean(type);
    }

    /** Creates an extra ACTIVE tenant (as the test superuser) so a test class can work in isolation. */
    public static UUID newTenant(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return TestDatabase.get().admin().queryForObject(
                "INSERT INTO app.tenant (slug, name, status) VALUES (?, ?, 'ACTIVE') RETURNING id", UUID.class, slug, slug);
    }
}
