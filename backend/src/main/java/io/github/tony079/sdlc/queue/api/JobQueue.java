package io.github.tony079.sdlc.queue.api;

import java.util.UUID;

/**
 * The PostgreSQL job queue (PRD R1-M1, TDD 10). Writes run in the caller's transaction and tenant context.
 * Claiming, leases, fencing and retries are internal to the worker.
 */
public interface JobQueue {

    /** Enqueues a job; returns false if a job with the same idempotency key already exists (no second job). */
    boolean enqueue(JobRequest request);

    /**
     * Cancels every unfinished job of an aggregate (TDD 9.9). The lease epoch is raised, so a worker still running
     * one of them can no longer commit. Returns the number of jobs cancelled.
     */
    int cancelForAggregate(UUID aggregateId);
}
