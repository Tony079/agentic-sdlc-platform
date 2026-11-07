package io.github.tony079.sdlc.modelgateway.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A versioned prompt (PRD FR-LLM-07, TDD 13.4). The instructions are trusted text from code; the gateway accepts a
 * template only if its id, version and SHA-256 are registered and ACTIVE in {@code prompt_version}.
 */
public record PromptTemplate(String id, int version, String instructions) {

    public PromptTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(instructions, "instructions");
        if (version < 1 || instructions.isBlank()) {
            throw new IllegalArgumentException("A prompt needs a version >= 1 and instructions");
        }
    }

    public String sha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(instructions.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
