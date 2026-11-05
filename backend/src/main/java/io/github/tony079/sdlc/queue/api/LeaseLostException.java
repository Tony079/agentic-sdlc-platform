package io.github.tony079.sdlc.queue.api;

/**
 * The worker no longer holds the lease it is trying to commit under: another epoch took over (LEASE_LOST), the
 * lease expired by the database clock (LEASE_EXPIRED) or the job was cancelled or finished (JOB_NOT_LEASED).
 * The commit transaction is rolled back; nothing of the worker's result is stored.
 */
public class LeaseLostException extends RuntimeException {

    private final String code;

    public LeaseLostException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
