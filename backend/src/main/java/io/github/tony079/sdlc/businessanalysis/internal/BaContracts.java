package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Strict structured-output contracts of the BA agent (TDD 12.3). Unknown fields are rejected by the gateway; these
 * constraints are checked by the gateway and again by the commit step. The output is untrusted data: no field can
 * trigger an action.
 */
final class BaContracts {

    private BaContracts() {
    }

    enum Kind { FACT, ASSUMPTION, RECOMMENDATION, QUESTION }

    enum Section { FACTS, ASSUMPTIONS, RISKS, IMPACTED_COMPONENTS, MISSING_REQUIREMENTS, RECOMMENDATIONS, QUESTIONS }

    enum EvidenceType { REPOSITORY, DOCUMENT }

    /** Phase A: inventory and relevance. */
    record PhaseA(@NotBlank @Size(max = 2000) String understanding,
                  @NotNull @Size(max = 20) List<@Valid Component> components,
                  @NotNull @Size(max = 15) List<@NotBlank @Size(max = 1024) String> relevantPaths) {
    }

    record Component(@NotBlank @Size(max = 200) String name,
                     @NotNull @Size(max = 50) List<@NotBlank @Size(max = 1024) String> paths) {
    }

    /** Phase B: the BRD proposal. */
    record Proposal(@NotNull @Size(max = 60) List<@Valid Item> items,
                    @NotNull @Size(max = 30) List<@Valid Unsupported> unsupported) {
    }

    record Item(@NotNull Kind kind, @NotNull Section section, @NotBlank @Size(max = 2000) String statement,
                @NotNull @Size(max = 5) List<@Valid Evidence> evidence, @Size(max = 500) String risk,
                @Size(max = 200) String component) {
    }

    record Evidence(@NotNull EvidenceType type, @Size(max = 1024) String path, @Min(1) @Max(1_000_000) Integer startLine,
                    @Min(1) @Max(1_000_000) Integer endLine, @Size(max = 36) String documentVersionId,
                    @Min(1) @Max(5000) Integer chunk, @NotBlank @Size(max = 300) String quote) {
    }

    record Unsupported(@NotBlank @Size(max = 1000) String requirement) {
    }
}
