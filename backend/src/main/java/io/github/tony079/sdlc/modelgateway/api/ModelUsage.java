package io.github.tony079.sdlc.modelgateway.api;

import java.math.BigDecimal;
import java.time.Duration;

/** Usage metadata of one gateway call, summed over its attempts (PRD FR-LLM-04). */
public record ModelUsage(int inputTokens, int outputTokens, BigDecimal costUsd, Duration latency, int attempts) {
}
