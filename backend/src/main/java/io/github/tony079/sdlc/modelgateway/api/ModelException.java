package io.github.tony079.sdlc.modelgateway.api;

import java.util.UUID;

/** A normalised gateway failure. Messages are fixed and content-free (no prompt, data or model output). */
public class ModelException extends RuntimeException {

    public enum Code {
        /** Provider or model not configured, disabled, unknown, or the prompt is not registered. Never retried. */
        CONFIGURATION_ERROR(false, false),
        /** The call would exceed a configured budget cap (FR-LLM-05). */
        BUDGET_EXCEEDED(false, false),
        /** All attempts timed out. A job should defer and retry later (TDD 13.5). */
        TIMEOUT(true, false),
        /** Provider unreachable or 5xx after the gateway's retries. A job should defer and retry later. */
        PROVIDER_UNAVAILABLE(true, false),
        /** Provider rate limit (429): defer without using the job's retry budget (TDD 13.5). */
        RATE_LIMITED(true, true),
        /** Output did not parse into, or validate against, the requested schema after the repair attempt. */
        INVALID_OUTPUT(false, false),
        /** The provider returned no content. */
        EMPTY_OUTPUT(false, false),
        /** Any other provider failure. */
        PROVIDER_ERROR(false, false);

        private final boolean transientFailure;
        private final boolean rateLimit;

        Code(boolean transientFailure, boolean rateLimit) {
            this.transientFailure = transientFailure;
            this.rateLimit = rateLimit;
        }

        /** Worth retrying later (as a job deferral), not a defect of the request. */
        public boolean transientFailure() {
            return transientFailure;
        }

        public boolean rateLimit() {
            return rateLimit;
        }
    }

    private final Code code;
    private final UUID requestId;

    public ModelException(Code code, UUID requestId, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
        this.requestId = requestId;
    }

    public Code code() {
        return code;
    }

    public UUID requestId() {
        return requestId;
    }
}
