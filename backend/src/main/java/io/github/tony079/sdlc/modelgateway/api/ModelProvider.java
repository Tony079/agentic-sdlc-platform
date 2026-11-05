package io.github.tony079.sdlc.modelgateway.api;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Provider adapter SPI (TDD 13.1). Implementations translate a call to one provider's API and nothing else: no
 * tenant data beyond the call, no tools, no database. A new adapter is added without changing agent code (FR-LLM-02).
 */
public interface ModelProvider {

    /** Stable provider name, matching {@code model_registry.provider}. */
    String name();

    Result complete(Call call) throws Failure;

    /** Trusted instructions and untrusted data are passed separately so the adapter can label them for the model. */
    record Call(UUID requestId, String correlationId, String modelId, String promptId, int promptVersion,
                String instructions, List<DataBlock> data, int maxOutputTokens, Duration timeout) {
    }

    /** Raw text (expected to be JSON) and the provider-reported token counts. */
    record Result(String text, int inputTokens, int outputTokens) {
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
