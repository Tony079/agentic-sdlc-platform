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

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OpenAiModelProvider.class);

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

    /** The secret holding the API key. */
    protected String secretName() {
        return "llm.openai-key";
    }

    protected java.net.URI chatCompletionsUri() {
        return HttpModelProviderSupport.endpoint(baseUrl, "/v1/chat/completions", allowLoopbackHttp);
    }

    protected Map<String, String> authHeaders(String key) {
        return Map.of("Authorization", "Bearer " + key);
    }

    @Override
    public Result complete(Call call) throws Failure {
        String key = secrets.secret(secretName()).orElseThrow(() -> new Failure(Failure.Kind.ERROR));
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
        if (call.tools().isEmpty() && call.outputSchema() != null) {
            // Strict structured output: the answer is constrained to the schema (enum labels, required fields).
            body.put("response_format", Map.of("type", "json_schema", "json_schema",
                    Map.of("name", "output", "strict", true, "schema", call.outputSchema())));
        } else if (call.tools().isEmpty()) {
            body.put("response_format", Map.of("type", "json_object"));
        } else {
            body.put("tools", call.tools().stream().map(t -> Map.of("type", "function", "function", Map.of(
                    "name", t.name(), "description", t.description(), "parameters",
                    Map.of("type", "object", "additionalProperties", Map.of("type", "string"))))).toList());
        }
        if (call.progress() != null && call.tools().isEmpty()) {
            return streamed(call, key, body);
        }
        String response = HttpModelProviderSupport.post(chatCompletionsUri(), authHeaders(key),
                json.writeValueAsString(body), call.timeout());
        JsonNode root = json.readTree(response);
        JsonNode choice = root.path("choices").path(0);
        String finish = choice.path("finish_reason").asString("");
        if ("content_filter".equals(finish)) {
            throw new Failure(Failure.Kind.ERROR);
        }
        if ("length".equals(finish)) {
            LOG.warn("{} output was cut off at the output token limit ({} tokens)", name(), call.maxOutputTokens());
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

    /**
     * GD-29: the same request, streamed. The text received so far is passed to the call's progress listener at most
     * every 400 ms (for display only); the complete text is returned exactly as from the non-streamed call.
     */
    private Result streamed(Call call, String key, Map<String, Object> body) throws Failure {
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        StringBuilder text = new StringBuilder();
        String[] finish = {""};
        Usage[] usage = {null};
        long[] lastReport = {0};
        HttpModelProviderSupport.postStream(chatCompletionsUri(), authHeaders(key), json.writeValueAsString(body),
                call.timeout(), line -> {
                    if (!line.startsWith("data:")) {
                        return;
                    }
                    String data = line.substring(5).strip();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        return;
                    }
                    JsonNode chunk;
                    try {
                        chunk = json.readTree(data);
                    } catch (RuntimeException e) {
                        return; // a malformed event is skipped; the final text is validated by the gateway anyway
                    }
                    JsonNode choice = chunk.path("choices").path(0);
                    JsonNode delta = choice.path("delta").path("content");
                    if (delta.isString()) {
                        text.append(delta.asString());
                        long now = System.nanoTime();
                        if (now - lastReport[0] > 400_000_000L) {
                            lastReport[0] = now;
                            call.progress().accept(text.toString());
                        }
                    }
                    String reason = choice.path("finish_reason").asString("");
                    if (!reason.isEmpty()) {
                        finish[0] = reason;
                    }
                    JsonNode u = chunk.path("usage");
                    if (u.isObject()) {
                        usage[0] = new Usage(u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0), false);
                    }
                });
        if ("content_filter".equals(finish[0])) {
            throw new Failure(Failure.Kind.ERROR);
        }
        if ("length".equals(finish[0])) {
            LOG.warn("{} output was cut off at the output token limit ({} tokens)", name(), call.maxOutputTokens());
        }
        call.progress().accept(text.toString());
        return new Result(text.isEmpty() ? null : text.toString(), usage[0], List.of());
    }
}
