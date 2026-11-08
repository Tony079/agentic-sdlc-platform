package io.github.tony079.sdlc.modelgateway.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.tony079.sdlc.common.api.SecretsProvider;
import io.github.tony079.sdlc.modelgateway.api.DataBlock;
import io.github.tony079.sdlc.modelgateway.api.ModelProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anthropic Messages API adapter (O-01 evaluation candidate; raw HTTP because the project adds no SDK dependency).
 * {@code POST /v1/messages} with {@code x-api-key} (secret {@code llm.anthropic-key}) and {@code anthropic-version}.
 * The model id comes only from {@code sdlc.model.model-id}; effort from {@code sdlc.model.anthropic.effort} when set.
 * Instructions go in {@code system}; untrusted data as separate text blocks of the user turn; tools as
 * {@code tools[]} with tool exchanges replayed as {@code tool_use}/{@code tool_result} blocks. A {@code refusal} stop
 * reason is reported as a provider error, never as output. NOT VERIFIED against the live API in this repository.
 */
@Component
@Profile("!migrate & !provision & !retention")
public class AnthropicModelProvider implements ModelProvider {

    static final String API_VERSION = "2026-10-21";

    private final SecretsProvider secrets;
    private final JsonMapper json;
    private final String baseUrl;
    private final boolean allowLoopbackHttp;
    private final String effort;

    public AnthropicModelProvider(SecretsProvider secrets, JsonMapper json,
                           @Value("${sdlc.model.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
                           @Value("${sdlc.model.allow-loopback-http:false}") boolean allowLoopbackHttp,
                           @Value("${sdlc.model.anthropic.effort:}") String effort) {
        this.secrets = secrets;
        this.json = json;
        this.baseUrl = baseUrl;
        this.allowLoopbackHttp = allowLoopbackHttp;
        this.effort = effort.strip();
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public Result complete(Call call) throws Failure {
        String key = secrets.secret("llm.anthropic-key").orElseThrow(() -> new Failure(Failure.Kind.ERROR));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", call.modelId());
        body.put("max_tokens", call.maxOutputTokens());
        body.put("system", call.instructions());
        if (!effort.isEmpty()) {
            body.put("output_config", Map.of("effort", effort));
        }
        List<Map<String, Object>> userContent = new ArrayList<>();
        for (DataBlock block : call.data()) {
            userContent.add(Map.of("type", "text", "text", HttpModelProviderSupport.dataText(block)));
        }
        if (userContent.isEmpty()) {
            userContent.add(Map.of("type", "text", "text", "(no data)"));
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", userContent));
        for (ToolExchange exchange : call.exchanges()) {
            messages.add(Map.of("role", "assistant", "content", List.of(Map.of("type", "tool_use",
                    "id", exchange.call().id(), "name", exchange.call().name(), "input", exchange.call().arguments()))));
            messages.add(Map.of("role", "user", "content", List.of(Map.of("type", "tool_result",
                    "tool_use_id", exchange.call().id(), "content", HttpModelProviderSupport.dataText(exchange.result())))));
        }
        body.put("messages", messages);
        if (!call.tools().isEmpty()) {
            body.put("tools", call.tools().stream().map(t -> Map.of("name", t.name(), "description", t.description(),
                    "input_schema", Map.of("type", "object", "additionalProperties", Map.of("type", "string"))))
                    .toList());
        }
        String response = HttpModelProviderSupport.post(
                HttpModelProviderSupport.endpoint(baseUrl, "/v1/messages", allowLoopbackHttp),
                Map.of("x-api-key", key, "anthropic-version", API_VERSION), json.writeValueAsString(body),
                call.timeout());
        JsonNode root = json.readTree(response);
        if ("refusal".equals(root.path("stop_reason").asString(""))) {
            throw new Failure(Failure.Kind.ERROR);
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode block : root.path("content")) {
            switch (block.path("type").asString("")) {
                case "text" -> text.append(block.path("text").asString(""));
                case "tool_use" -> {
                    Map<String, String> args = new LinkedHashMap<>();
                    block.path("input").properties().forEach(e -> args.put(e.getKey(),
                            e.getValue().isString() ? e.getValue().asString() : e.getValue().toString()));
                    toolCalls.add(new ToolCall(block.path("id").asString(""), block.path("name").asString(""), args));
                }
                default -> { }
            }
        }
        JsonNode usage = root.path("usage");
        Usage measured = usage.isObject() ? new Usage(usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0), false) : null;
        return new Result(text.isEmpty() ? null : text.toString(), measured, toolCalls);
    }
}
