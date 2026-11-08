package io.github.tony079.sdlc.modelgateway.api;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Provider adapter SPI (TDD 13.1). Implementations translate a call to one provider's API and nothing else: no
 * tenant data beyond the call, no database. An adapter maps {@link ToolSpec}s, tool calls and their results onto the
 * provider's native tool protocol; it never runs a tool itself (the gateway does). A new adapter is added without
 * changing agent code (FR-LLM-02).
 */
public interface ModelProvider {

    /** Stable provider name, matching {@code model_registry.provider}. */
    String name();

    Result complete(Call call) throws Failure;

    /** Trusted instructions and untrusted data are passed separately so the adapter can label them for the model. */
    record Call(UUID requestId, String correlationId, String modelId, String promptId, int promptVersion,
                String instructions, List<DataBlock> data, int maxOutputTokens, Duration timeout,
                List<ToolSpec> tools, List<ToolExchange> exchanges) {

        /** A call without tools. */
        public Call(UUID requestId, String correlationId, String modelId, String promptId, int promptVersion,
                    String instructions, List<DataBlock> data, int maxOutputTokens, Duration timeout) {
            this(requestId, correlationId, modelId, promptId, promptVersion, instructions, data, maxOutputTokens, timeout,
                    List.of(), List.of());
        }

        public Call {
            tools = tools == null ? List.of() : List.copyOf(tools);
            exchanges = exchanges == null ? List.of() : List.copyOf(exchanges);
        }
    }

    /** A tool offered to the model: trusted name and description only. */
    record ToolSpec(String name, String description) {
    }

    /** A tool call the model asked for. {@code id} is the provider's call id (echoed back), arguments are untrusted. */
    record ToolCall(String id, String name, java.util.Map<String, String> arguments) {
        public ToolCall {
            arguments = arguments == null ? java.util.Map.of() : java.util.Map.copyOf(arguments);
        }
    }

    /** An earlier tool call and its result, which the adapter sends back as untrusted data. */
    record ToolExchange(ToolCall call, DataBlock result) {
    }

    /**
     * Raw text (expected to be JSON) and the usage the provider returned with it.
     *
     * @param usage null when the provider returned no usage data; the gateway then records tokens and cost as unknown,
     *              never as zero
     */
    record Result(String text, Usage usage, List<ToolCall> toolCalls) {

        public Result(String text, Usage usage) {
            this(text, usage, List.of());
        }

        public Result {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    /**
     * Token counts for one result.
     *
     * @param estimated true when the counts were computed locally rather than reported by the provider (the fake
     *                  provider); recorded as ESTIMATED, never as a provider measurement
     */
    record Usage(int inputTokens, int outputTokens, boolean estimated) {

        public Usage {
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("Token counts cannot be negative");
            }
        }
    }

    class Failure extends Exception {

        public enum Kind { TIMEOUT, UNAVAILABLE, RATE_LIMITED, ERROR }

        private final Kind kind;

        public Failure(Kind kind) {
            super(kind.name());
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
