package io.github.tony079.sdlc.modelgateway.api;

import java.util.Objects;

/**
 * Untrusted content given to the model as data (PRD section 11): repository files, document chunks, client text.
 * It is always sent separately from the trusted instructions and clearly labelled; text inside it is never treated
 * as an instruction by the platform.
 *
 * @param label  source reference (for example a document version and chunk ordinal); never an instruction
 */
public record DataBlock(String label, String content) {

    public DataBlock {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(content, "content");
    }
}
