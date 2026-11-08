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
 * OpenAI Chat Completions adapter (O-01 evaluation candidate). {@code POST /v1/chat/completions} with a bearer key
 * (secret {@code llm.openai-key}); model only from {@code sdlc.model.model-id}; JSON object output requested; untrusted
 * data as separate user messages; tools as functions with exchanges replayed as assistant {@code tool_calls} and
 * {@code tool} messages. A {@code content_filter} finish reason is a provider error. Written from the documented API
 * shape; NOT VERIFIED against the live API in this repository.
 */
@Component
@Profile("!migrate & !provision & !retention")
public class OpenAiModelProvider implements ModelProvider {

    private final SecretsProvider secrets;
    private final JsonMapper json;
    private final String baseUrl;
    private final boolean allowLoopbackHttp;

    public OpenAiModelProvider(SecretsProvider secrets, JsonMapper json,
                        @Value("${sdlc.model.openai.base-url:https://api.openai.com}") String baseUrl,
                        @Value("${sdlc.model.allow-loopback-http:false}") boolean allowLoopbackHttp) {
        this.secrets = secrets;
        this.json = json;
        this.baseUrl = baseUrl;
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public Result complete(Call call) throws Failure {
        String key = secrets.secret("llm.openai-key").orElseThrow(() -> new Failure(Failure.Kind.ERROR));
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", call.instructions()));
        for (DataBlock block : call.data()) {
            messages.add(Map.of("role", "user", "content", HttpModelProviderSupport.dataText(block)));
        }
        for (ToolExchange exchange : call.exchanges()) {
            messages.add(Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                    "id", exchange.call().id(), "type", "function", "function", Map.of("name", exchange.call().name(),
                            "arguments", json.writeValueAsString(exchange.call().arguments()))))));
            messages.add(Map.of("role", "tool", "tool_call_id", exchange.call().id(),
                    "content", HttpModelProviderSupport.dataText(exchange.result())));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", call.modelId());
        body.put("max_completion_tokens", call.maxOutputTokens());
        body.put("messages", messages);
        if (call.tools().isEmpty()) {
            body.put("response_format", Map.of("type", "json_object"));
        } else {
            body.put("tools", call.tools().stream().map(t -> Map.of("type", "function", "function", Map.of(
                    "name", t.name(), "description", t.description(), "parameters",
                    Map.of("type", "object", "additionalProperties", Map.of("type", "string"))))).toList());
        }
        String response = HttpModelProviderSupport.post(
                HttpModelProviderSupport.endpoint(baseUrl, "/v1/chat/completions", allowLoopbackHttp),
                Map.of("Authorization", "Bearer " + key), json.writeValueAsString(body), call.timeout());
        JsonNode root = json.readTree(response);
        JsonNode choice = root.path("choices").path(0);
        if ("content_filter".equals(choice.path("finish_reason").asString(""))) {
            throw new Failure(Failure.Kind.ERROR);
        }
        JsonNode message = choice.path("message");
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode tc : message.path("tool_calls")) {
            Map<String, String> args = new LinkedHashMap<>();
            try {
                json.readTree(tc.path("function").path("arguments").asString("{}")).properties()
                        .forEach(e -> args.put(e.getKey(),
                                e.getValue().isString() ? e.getValue().asString() : e.getValue().toString()));
            } catch (RuntimeException e) {
                // Malformed arguments: passed on empty; the tool refuses invalid arguments itself.
            }
            toolCalls.add(new ToolCall(tc.path("id").asString(""), tc.path("function").path("name").asString(""), args));
        }
        String text = message.path("content").isString() ? message.path("content").asString() : null;
        JsonNode usage = root.path("usage");
        Usage measured = usage.isObject() ? new Usage(usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0), false) : null;
        return new Result(text == null || text.isEmpty() ? null : text, measured, toolCalls);
    }
}
