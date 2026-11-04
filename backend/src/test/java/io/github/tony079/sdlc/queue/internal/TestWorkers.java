package io.github.tony079.sdlc.queue.internal;

import java.util.List;

import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.support.WorkerContext;
import io.github.tony079.sdlc.tenancy.api.TenantLookup;

import org.springframework.transaction.PlatformTransactionManager;

/** Test access to job workers with custom handlers, from tests in other packages. */
public final class TestWorkers {

    private TestWorkers() {
    }

    public static JobWorker create(List<JobHandler<?>> handlers) {
        return new JobWorker(WorkerContext.bean(JdbcJobStore.class), WorkerContext.bean(TenantLookup.class), handlers,
                WorkerContext.bean(PlatformTransactionManager.class), 1);
    }

    public static void shutdown(JobWorker worker) {
        try {
            worker.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
