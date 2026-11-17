package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;

import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Evidence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Structured-output contracts of the Architect agent (GD-26). Like the BA agent's, the output is untrusted data:
 * the engine verifies every citation, drops BRD references that do not exist and computes coverage itself.
 */
final class DesignContracts {

    private DesignContracts() {
    }

    enum Section { ARCHITECTURE, COMPONENTS, API_CHANGES, DATA_MODEL, INTEGRATIONS, SECURITY, NON_FUNCTIONAL,
        MIGRATION_AND_ROLLOUT, TEST_PLAN, RISKS }

    enum Kind { DECISION, CHANGE, NEW, TASK, RISK, ASSUMPTION }

    /** Call 1: the files the design must read. */
    record Files(@NotBlank @Size(max = 2000) String approach,
                 @NotNull @Size(max = 20) List<@NotBlank @Size(max = 1024) String> relevantPaths) {
    }

    /** Call 2: the design proposal. */
    record Proposal(@NotBlank @Size(max = 3000) String summary, @NotNull @Size(max = 60) List<@Valid Item> items,
                    @NotNull @Size(max = 10) List<@NotBlank @Size(max = 1000) String> openQuestions) {
    }

    record Item(@NotNull Section section, @NotNull Kind kind, @NotBlank @Size(max = 200) String title,
                @NotBlank @Size(max = 3000) String detail, @Size(max = 200) String component,
                @NotNull @Size(max = 30) List<@NotBlank @Size(max = 100) String> brdRefs,
                @NotNull @Size(max = 5) List<@Valid Evidence> evidence) {
    }
}
