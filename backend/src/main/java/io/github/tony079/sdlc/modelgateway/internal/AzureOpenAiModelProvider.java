package io.github.tony079.sdlc.modelgateway.internal;

import java.net.URI;
import java.util.Map;

import io.github.tony079.sdlc.common.api.SecretsProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Azure OpenAI in Microsoft Foundry (O-01 provider chosen by the owner, 2026-11-16). Same Chat Completions shape as
 * {@link OpenAiModelProvider}, sent to the resource's OpenAI v1 endpoint
 * ({@code https://<resource>.openai.azure.com/openai/v1/chat/completions}) with the {@code api-key} header (secret
 * {@code llm.azure-openai-key}). The model is the deployment name ({@code sdlc.model.model-id}). Only Azure hosts are
 * accepted; whatever URL is configured (resource endpoint or a deployment's target URI), only its host is used.
 */
@Component
@Profile("!migrate & !provision & !retention")
public class AzureOpenAiModelProvider extends OpenAiModelProvider {

    private final String endpoint;
    private final boolean loopback;

    public AzureOpenAiModelProvider(SecretsProvider secrets, JsonMapper json,
                                    @Value("${sdlc.model.azure.endpoint:}") String endpoint,
                                    @Value("${sdlc.model.allow-loopback-http:false}") boolean allowLoopbackHttp) {
        super(secrets, json, "https://unused.invalid", allowLoopbackHttp);
        this.endpoint = endpoint == null ? "" : endpoint.strip();
        this.loopback = allowLoopbackHttp;
    }

    @Override
    public String name() {
        return "azure-openai";
    }

    @Override
    protected String secretName() {
        return "llm.azure-openai-key";
    }

    @Override
    protected URI chatCompletionsUri() {
        return uri(endpoint, loopback);
    }

    @Override
    protected Map<String, String> authHeaders(String key) {
        return Map.of("api-key", key);
    }

    /** The resource host of any Foundry or Azure OpenAI URL, with the OpenAI v1 chat completions path. */
    static URI uri(String configured, boolean allowLoopbackHttp) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("sdlc.model.azure.endpoint is not set");
        }
        URI given = URI.create(configured.strip());
        String host = given.getHost();
        boolean local = allowLoopbackHttp && ("localhost".equals(host) || "127.0.0.1".equals(host));
        if (host == null || !(local || host.endsWith(".openai.azure.com") || host.endsWith(".cognitiveservices.azure.com")
                || host.endsWith(".services.ai.azure.com"))) {
            throw new IllegalStateException("sdlc.model.azure.endpoint must be an Azure OpenAI or Foundry URL");
        }
        String base = given.getScheme() + "://" + host + (given.getPort() > 0 ? ":" + given.getPort() : "");
        return HttpModelProviderSupport.endpoint(base, "/openai/v1/chat/completions", allowLoopbackHttp);
    }
}
