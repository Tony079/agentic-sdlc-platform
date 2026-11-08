package io.github.tony079.sdlc.modelgateway.api;

import java.util.Map;

/**
 * A read-only tool the model may call during one gateway request (PRD FR-LLM-01, TDD 12.5). Tools are trusted Java
 * services; the job binds the set it allows to the request (the registry), and the model can never add one. The
 * gateway checks the name against that set, bounds the number of calls and the result size, and hands the result back
 * to the model as untrusted data. A tool must check its own arguments, stay within the job's tenant and snapshot, and
 * never reach the network or write anything.
 */
public interface ModelTool {

    /** Stable name, {@code ^[a-z][a-z0-9_.]{1,63}$}. */
    String name();

    /** Trusted, fixed description shown to the model, including the argument names. */
    String description();

    /**
     * Runs the tool. Arguments come from the model and are untrusted.
     *
     * @throws Refused with a short, fixed reason when the arguments are invalid or out of scope
     */
    String invoke(Map<String, String> arguments) throws Refused;

    /** Result size cap in bytes (UTF-8); longer results are cut. TDD 12.5: 64 KB repository range, 16 KB chunk. */
    default int maxResultBytes() {
        return 16 * 1024;
    }

    /** A refusal the model may see. The reason must be fixed text, never data. */
    class Refused extends Exception {
        public Refused(String reason) {
            super(reason);
        }
    }
}
