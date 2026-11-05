package io.github.tony079.sdlc.queue.api;

import java.util.Map;
import java.util.UUID;

/** A job held under a lease by one worker. {@code leaseEpoch} is the fencing token of this lease. */
public record ClaimedJob(UUID id, UUID tenantId, String jobType, String aggregateType, UUID aggregateId,
                         UUID stageExecutionId, Map<String, Object> payload, String workerId, long leaseEpoch,
                         int attemptNo) {
}
