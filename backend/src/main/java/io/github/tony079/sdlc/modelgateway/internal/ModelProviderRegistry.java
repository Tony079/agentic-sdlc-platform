package io.github.tony079.sdlc.modelgateway.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.tony079.sdlc.modelgateway.api.ModelProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the provider named by server configuration ({@code sdlc.model.provider}); never from a request. "none"
 * (the default while O-01 is open) means no provider is configured: every call fails with CONFIGURATION_ERROR and no
 * data leaves the platform. An unknown name stops the application at start-up with a clear message.
 */
@Component
class ModelProviderRegistry {

    static final String NONE = "none";

    private final Map<String, ModelProvider> providers;
    private final String configured;
    private final String modelId;

    ModelProviderRegistry(List<ModelProvider> providers, @Value("${sdlc.model.provider:none}") String configured,
                          @Value("${sdlc.model.model-id:}") String modelId) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(ModelProvider::name, Function.identity()));
        this.configured = configured.strip();
        this.modelId = modelId.strip();
        if (!NONE.equals(this.configured) && !this.providers.containsKey(this.configured)) {
            throw new IllegalStateException("sdlc.model.provider '" + this.configured + "' is not supported; available: "
                    + this.providers.keySet() + " or 'none'");
        }
        if (!NONE.equals(this.configured) && this.modelId.isEmpty()) {
            throw new IllegalStateException("sdlc.model.model-id must be set when sdlc.model.provider is '"
                    + this.configured + "'");
        }
    }

    /** The configured provider, or empty when none is configured. */
    Optional<ModelProvider> provider() {
        return Optional.ofNullable(providers.get(configured));
    }

    String modelId() {
        return modelId;
    }
}
