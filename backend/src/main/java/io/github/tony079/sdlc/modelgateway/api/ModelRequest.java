package io.github.tony079.sdlc.modelgateway.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A typed model request. The provider and model come from server configuration, never from the request, and the
 * tenant comes from the trusted tenant context (decision D-GW-03).
 *
 * @param outputType      record type the model's JSON output must bind to (unknown fields are rejected); its Bean
 *                        Validation constraints are checked afterwards
 * @param requirementId   optional, for usage records and budget caps per requirement
 * @param maxOutputTokens upper bound requested from the provider
 * @param timeout         per-attempt timeout; null uses the configured default
 */
public record ModelRequest<T>(PromptTemplate prompt, List<DataBlock> data, Class<T> outputType, UUID requirementId,
                              int maxOutputTokens, Duration timeout) {

    public ModelRequest {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(outputType, "outputType");
        data = data == null ? List.of() : List.copyOf(data);
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }

    public static <T> ModelRequest<T> of(PromptTemplate prompt, List<DataBlock> data, Class<T> outputType) {
        return new ModelRequest<>(prompt, data, outputType, null, 2048, null);
    }
}
