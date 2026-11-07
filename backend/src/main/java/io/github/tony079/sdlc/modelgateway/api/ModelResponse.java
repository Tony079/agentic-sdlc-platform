package io.github.tony079.sdlc.modelgateway.api;

import java.util.UUID;

/**
 * A validated, normalised model result. The output is data only: it cannot change workflow state, approve anything
 * or call tools; callers decide what to do with it through their own engine commands.
 */
public record ModelResponse<T>(T output, UUID requestId, String correlationId, String provider, String modelId,
                               String promptId, int promptVersion, ModelUsage usage) {
}
