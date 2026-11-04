package io.github.tony079.sdlc.queue.api;

import java.time.Duration;
import java.util.Objects;

/** A classified job failure (TDD 10.6). The code is content-free (upper case with underscores). */
public class JobFailure extends Exception {

    public enum Kind {
        /** Worth retrying with backoff; uses the retry budget. */
        RETRYABLE,
        /** Can never succeed; the job goes DEAD at once. */
        FATAL,
        /** A dependency is unavailable (for example the scanner): requeued after a delay, no budget used. */
        DEPENDENCY_UNAVAILABLE
    }

    private final Kind kind;
    private final String code;
    private final Duration delay;

    private JobFailure(Kind kind, String code, Duration delay, Throwable cause) {
        super(code, cause);
        this.kind = Objects.requireNonNull(kind);
        if (code == null || !code.matches("^[A-Z][A-Z0-9_]{1,63}$")) {
            throw new IllegalArgumentException("Invalid job failure code");
        }
        this.code = code;
        this.delay = delay;
    }

    public static JobFailure retryable(String code, Throwable cause) {
        return new JobFailure(Kind.RETRYABLE, code, null, cause);
    }

    public static JobFailure fatal(String code, Throwable cause) {
        return new JobFailure(Kind.FATAL, code, null, cause);
    }

    public static JobFailure dependencyUnavailable(String code, Duration delay) {
        return new JobFailure(Kind.DEPENDENCY_UNAVAILABLE, code, Objects.requireNonNull(delay), null);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public Duration delay() {
        return delay;
    }
}
