package io.github.tony079.sdlc.sourcerepo.internal;

import java.time.Duration;
import java.util.UUID;

import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;

/** Shared job conventions of the repository stage: keys, payload access and GitHub failure mapping. */
final class RepoJobs {

    static final String VERIFY = "REPO_VERIFY";
    static final String VALIDATE = "REPO_VALIDATE";
    static final String SNAPSHOT = "REPO_SNAPSHOT";
    static final String INDEX = "REPO_INDEX";
    static final Duration NOT_READY_DELAY = Duration.ofMinutes(5);
    static final Duration NOT_CONFIGURED_DELAY = Duration.ofMinutes(10);

    private RepoJobs() {
    }

    /** The key T02 uses for the requirement's first validation job (JdbcWorkflowEngine.route). */
    static String validateKey(UUID requirementId) {
        return "repo-validate:" + requirementId + ":1";
    }

    static UUID uuid(ClaimedJob job, String attribute) throws JobFailure {
        Object value = job.payload().get(attribute);
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw JobFailure.fatal("PAYLOAD_INVALID", null);
        }
    }

    /** Rate limits and a disabled integration defer (no budget); transient errors retry; the rest are fatal. */
    static JobFailure failure(GitHubException e) {
        return switch (e.kind()) {
            case RATE_LIMITED -> JobFailure.dependencyUnavailable(e.code(), e.delay());
            case NOT_CONFIGURED -> JobFailure.dependencyUnavailable(e.code(), NOT_CONFIGURED_DELAY);
            case TRANSIENT -> JobFailure.retryable(e.code(), null);
            case ACCESS_DENIED -> JobFailure.fatal("REPO_ACCESS", null);
            case UNAVAILABLE, INVALID_RESPONSE -> JobFailure.fatal(e.code(), null);
        };
    }
}
