package io.github.tony079.sdlc.common.api;

import java.time.Duration;

import org.springframework.http.HttpStatus;

/** 429 RATE_LIMITED with a Retry-After hint (TDD 20.1). */
public class RateLimitedException extends ApiException {

    private final Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Try again later.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
