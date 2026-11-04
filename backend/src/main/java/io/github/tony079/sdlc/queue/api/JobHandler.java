package io.github.tony079.sdlc.queue.api;

/**
 * Executes one job type in two phases (TDD 10.4, 10.5).
 *
 * <ol>
 *   <li>{@link #execute} does the work outside any database transaction while the worker keeps the lease alive
 *       with heartbeats. It must not write business state.</li>
 *   <li>{@link #commit} writes the result inside the fenced transaction: the worker first locks the job row and
 *       verifies it still holds the lease (owner, epoch, not expired by the database clock); the result and the
 *       job's completion then commit together. An expired or superseded worker cannot commit.</li>
 * </ol>
 *
 * <p>Failures: throw {@link JobFailure} with the outcome class. Effects must be idempotent: a job can run again
 * after a crash or lost lease, so {@link #commit} must tolerate a result that already exists.
 *
 * @param <R> the prepared result passed from execute to commit
 */
public interface JobHandler<R> {

    String jobType();

    R execute(ClaimedJob job) throws JobFailure;

    void commit(ClaimedJob job, R result);
}
