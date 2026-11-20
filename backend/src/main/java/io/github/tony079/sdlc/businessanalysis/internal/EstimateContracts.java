package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * GD-33: what the Estimation agent may return (prompt est.items v1). Figures are proposals only: the engine clips and
 * flags them, and computes every total, timeline and cost itself.
 */
final class EstimateContracts {

    private EstimateContracts() {
    }

    enum Role { FRONTEND, BACKEND, DATA, DEVOPS, QA, DESIGN, OTHER }

    enum Phase { SETUP, BUILD, TEST, HARDENING, RELEASE }

    record Proposal(@NotNull @Size(max = 250) List<@Valid Item> items,
                    @NotNull @Size(max = 40) List<@NotBlank String> assumptions,
                    @NotNull @Size(max = 40) List<@NotBlank String> excluded) {
        Proposal {
            assumptions = Lenient.clipAll(assumptions, 40, 500);
            excluded = Lenient.clipAll(excluded, 40, 500);
        }
    }

    record Item(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 1000) String detail,
                @NotNull Role role, @NotNull Phase phase,
                @NotNull @Size(max = 30) List<@NotBlank @Size(max = 100) String> designRefs,
                @NotNull Double lowDays, @NotNull Double likelyDays, @NotNull Double highDays,
                @NotNull @Size(max = 20) List<@NotBlank String> assumptions, @Size(max = 1000) String risk,
                @NotNull @Size(max = 30) List<@NotBlank String> dependsOn) {
        Item {
            title = Lenient.clip(title, 200);
            detail = Lenient.clip(detail, 1000);
            designRefs = Lenient.clipAll(designRefs, 30, 100);
            assumptions = Lenient.clipAll(assumptions, 20, 300);
            risk = Lenient.clip(risk, 1000);
            dependsOn = Lenient.clipAll(dependsOn, 30, 200);
        }
    }
}
