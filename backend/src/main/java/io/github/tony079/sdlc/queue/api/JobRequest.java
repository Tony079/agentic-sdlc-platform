package io.github.tony079.sdlc.queue.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A job to enqueue. The idempotency key is unique per tenant: enqueueing the same key twice creates one job.
 * The payload holds identifiers and codes only. Retry policy comes from the job type, never from the caller.
 */
public record JobRequest(String jobType, String idempotencyKey, String aggregateType, UUID aggregateId,
                         UUID stageExecutionId, Map<String, Object> payload, int priority) {

    public JobRequest {
        Objects.requireNonNull(jobType, "jobType");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
