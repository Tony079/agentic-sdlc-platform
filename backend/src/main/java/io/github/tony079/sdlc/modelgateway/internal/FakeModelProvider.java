package io.github.tony079.sdlc.modelgateway.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import io.github.tony079.sdlc.modelgateway.api.ModelProvider;

import org.springframework.stereotype.Component;

/**
 * Deterministic fake provider (PRD FR-LLM-09, TDD 13.1): no network, no randomness, no tools, no dependencies.
 * Behaviour comes from a script (consumed in order) or a canned reply per prompt id; otherwise it returns an empty
 * reply. Token counts are derived from text length (ceil(chars / 4)), so usage figures are reproducible; they are
 * marked as estimated, never as provider measurements.
 * Selected only when {@code sdlc.model.provider=fake}; its registry entry has data class FAKE.
 */
@Component
public class FakeModelProvider implements ModelProvider {

    /** One scripted behaviour for the next call. */
    public sealed interface Behaviour permits Reply, ReplyWithoutUsage, Fail, Hang {
    }

    /** Return this text (valid JSON, invalid JSON, empty: whatever the scenario needs). */
    public record Reply(String text) implements Behaviour {
    }

    /** Return this text without any usage data, like a provider that omits it. */
    public record ReplyWithoutUsage(String text) implements Behaviour {
    }

    /** Fail with the given kind (timeout, unavailable, rate limit, error). */
    public record Fail(Failure.Kind kind) implements Behaviour {
    }

    /** Block for this long, ignoring the call's timeout, to prove the gateway enforces it. */
    public record Hang(Duration duration) implements Behaviour {
    }

    private final ConcurrentLinkedDeque<Behaviour> script = new ConcurrentLinkedDeque<>();
    private final Map<String, String> canned = new ConcurrentHashMap<>();
    private final List<Call> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "fake";
    }

    public void enqueue(Behaviour... behaviours) {
        script.addAll(List.of(behaviours));
    }

    public void cannedReply(String promptId, String json) {
        canned.put(promptId, json);
    }

    public void reset() {
        script.clear();
        canned.clear();
        calls.clear();
    }

    /** Calls received, for assertions (what the provider was given, never what a tool did). */
    public List<Call> calls() {
        return new ArrayList<>(calls);
    }

    @Override
    public Result complete(Call call) throws Failure {
        calls.add(call);
        Behaviour behaviour = script.poll();
        if (behaviour == null) {
            behaviour = new Reply(canned.getOrDefault(call.promptId(), ""));
        }
        return switch (behaviour) {
            case Reply reply -> new Result(reply.text(), new Usage(tokens(call), estimate(reply.text()), true));
            case ReplyWithoutUsage reply -> new Result(reply.text(), null);
            case Fail fail -> throw new Failure(fail.kind());
            case Hang hang -> {
                try {
                    Thread.sleep(hang.duration());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Failure(Failure.Kind.TIMEOUT);
                }
                yield new Result("{}", new Usage(tokens(call), 1, true));
            }
        };
    }

    private static int tokens(Call call) {
        int chars = call.instructions().length();
        for (var block : call.data()) {
            chars += block.label().length() + block.content().length();
        }
        return estimate(chars);
    }

    private static int estimate(String text) {
        return estimate(text == null ? 0 : text.length());
    }

    private static int estimate(int chars) {
        return (chars + 3) / 4;
    }
}
