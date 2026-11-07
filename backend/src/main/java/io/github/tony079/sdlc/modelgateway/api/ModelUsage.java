package io.github.tony079.sdlc.modelgateway.api;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Usage metadata of one gateway call, summed over its attempts (PRD FR-LLM-04). Tokens and cost are null when they
 * are not known; {@link #source} says where known values come from.
 */
public record ModelUsage(Source source, Integer inputTokens, Integer outputTokens, BigDecimal costUsd,
                         Duration latency, int attempts) {

    public enum Source {
        /** All results carried the provider's own token counts. */
        REPORTED,
        /** Counts were computed locally (fake provider); not a provider measurement. */
        ESTIMATED,
        /** At least one result carried no usage data: tokens and cost are unknown. */
        UNAVAILABLE,
        /** No provider result at all (failure, or refusal before any call). */
        NONE
    }
}
