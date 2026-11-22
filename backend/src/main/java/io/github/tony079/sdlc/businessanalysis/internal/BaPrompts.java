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
    /**
     * v4: v3 (GD-24 template and feasibility verdict) with explicit kind and section values; earlier versions stay
     * registered but unused.
     */
    static final PromptTemplate PHASE_B = load("ba.phase-b", 8, "prompts/ba-phase-b-v8.txt");

    /** GD-26 Architect agent: choose the code to read, then write the technical design. */
    static final PromptTemplate ARCH_FILES = load("arch.files", 1, "prompts/arch-files-v1.txt");
    static final PromptTemplate ARCH_DESIGN = load("arch.design", 5, "prompts/arch-design-v5.txt");

    /** GD-33 Estimation agent: work items with effort ranges from the approved design. */
    static final PromptTemplate EST_ITEMS = load("est.items", 1, "prompts/est-items-v1.txt");
    /** GD-35: agent delivery mode (coding agents build, people review). */
    static final PromptTemplate EST_ITEMS_AGENT = load("est.items", 2, "prompts/est-items-v2.txt");

    /** GD-34 Planning agent: epics and stories from the approved design and estimate. */
    /** GD-36: the Developer agent, one session of work on a story. */
    static final PromptTemplate DEV_STORY = load("dev.story", 4, "prompts/dev-story-v4.txt");
    static final PromptTemplate PLAN_BACKLOG = load("plan.backlog", 1, "prompts/plan-backlog-v1.txt");

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
