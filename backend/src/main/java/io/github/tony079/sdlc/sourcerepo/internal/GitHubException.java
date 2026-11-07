package io.github.tony079.sdlc.sourcerepo.internal;

import java.time.Duration;

/** A classified GitHub failure. The code is content-free; nothing from the response body is kept. */
class GitHubException extends Exception {

    enum Kind {
        /** The App is not installed on the repository, or GitHub answered 403/404: no access (REPO_ACCESS). */
        ACCESS_DENIED,
        /** The repository moved, or its identity no longer matches the verified one. */
        UNAVAILABLE,
        /** Rate limited: retry after {@link #delay()} without using the retry budget. */
        RATE_LIMITED,
        /** 5xx, timeouts, connection errors: retryable. */
        TRANSIENT,
        /** A response or redirect violates the broker's rules (host, size, shape, token scope). */
        INVALID_RESPONSE,
        /** The integration is disabled or not configured. */
        NOT_CONFIGURED
    }

    private final Kind kind;
    private final String code;
    private final Duration delay;

    GitHubException(Kind kind, String code) {
        this(kind, code, null);
    }

    GitHubException(Kind kind, String code, Duration delay) {
        super(code);
        this.kind = kind;
        this.code = code;
        this.delay = delay;
    }

    Kind kind() {
        return kind;
    }

    String code() {
        return code;
    }

    Duration delay() {
        return delay;
    }
}
