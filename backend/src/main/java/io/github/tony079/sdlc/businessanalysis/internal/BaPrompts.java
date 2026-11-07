package io.github.tony079.sdlc.businessanalysis.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.tony079.sdlc.modelgateway.api.PromptTemplate;

/**
 * The versioned BA prompts (TDD 13.4). Trusted text from classpath resources; their SHA-256 is registered in
 * prompt_version (changeset 0020) and checked by the gateway on every call. A text change needs a new version.
 */
final class BaPrompts {

    static final PromptTemplate PHASE_A = load("ba.phase-a", 1, "prompts/ba-phase-a-v1.txt");
    static final PromptTemplate PHASE_B = load("ba.phase-b", 1, "prompts/ba-phase-b-v1.txt");

    private BaPrompts() {
    }

    private static PromptTemplate load(String id, int version, String resource) {
        try (InputStream in = BaPrompts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing prompt resource " + resource);
            }
            return new PromptTemplate(id, version, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read prompt resource " + resource, e);
        }
    }

    static String ref(PromptTemplate prompt) {
        return prompt.id() + "@" + prompt.version();
    }
}
