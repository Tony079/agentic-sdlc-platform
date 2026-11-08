package io.github.tony079.sdlc.modelgateway.api;

import io.github.tony079.sdlc.common.api.SecretsProvider;
import io.github.tony079.sdlc.modelgateway.internal.AnthropicModelProvider;
import io.github.tony079.sdlc.modelgateway.internal.OpenAiModelProvider;

import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the hosted provider adapters outside the Spring context, for the provider evaluation harness (O-01). Keys
 * come only from the {@link SecretsProvider}; base URLs default to the providers' public HTTPS endpoints.
 */
public final class HostedProviders {

    private HostedProviders() {
    }

    public static ModelProvider create(String name, SecretsProvider secrets, String baseUrl, boolean allowLoopbackHttp) {
        JsonMapper json = new JsonMapper();
        return switch (name) {
            case "anthropic" -> new AnthropicModelProvider(secrets, json,
                    baseUrl == null ? "https://api.anthropic.com" : baseUrl, allowLoopbackHttp, "");
            case "openai" -> new OpenAiModelProvider(secrets, json,
                    baseUrl == null ? "https://api.openai.com" : baseUrl, allowLoopbackHttp);
            default -> throw new IllegalArgumentException("unknown provider " + name);
        };
    }
}
