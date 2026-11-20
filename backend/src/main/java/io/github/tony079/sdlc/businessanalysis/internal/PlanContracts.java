package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * GD-34: what the Planning agent may return (prompt plan.backlog v1). No effort, sprints or dates: the engine
 * derives effort from the approved estimate and lays out sprints from the team's capacity.
 */
final class PlanContracts {

    private PlanContracts() {
    }

    enum Priority { MUST, SHOULD, COULD }

    record Proposal(@NotNull @Size(max = 25) List<@Valid Epic> epics, @NotNull @Size(max = 300) List<@Valid Story> stories,
                    @NotNull @Size(max = 40) List<@NotBlank String> risks) {
        Proposal {
            risks = Lenient.clipAll(risks, 40, 500);
        }
    }

    record Epic(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 600) String goal,
                @NotNull @Size(max = 60) List<@NotBlank @Size(max = 100) String> designRefs) {
        Epic {
            title = Lenient.clip(title, 200);
            goal = Lenient.clip(goal, 600);
            designRefs = Lenient.clipAll(designRefs, 60, 100);
        }
    }

    record Story(@NotBlank @Size(max = 200) String epic, @NotBlank @Size(max = 300) String title,
                 @NotNull @Size(max = 12) List<@NotBlank String> acceptanceCriteria,
                 @NotNull @Size(max = 30) List<@NotBlank @Size(max = 100) String> estimateRefs,
                 @NotNull @Size(max = 30) List<@NotBlank @Size(max = 100) String> brdRefs,
                 @NotNull EstimateContracts.Role role, @NotNull Priority priority,
                 @NotNull @Size(max = 30) List<@NotBlank String> dependsOn) {
        Story {
            epic = Lenient.clip(epic, 200);
            title = Lenient.clip(title, 300);
            acceptanceCriteria = Lenient.clipAll(acceptanceCriteria, 12, 400);
            estimateRefs = Lenient.clipAll(estimateRefs, 30, 100);
            brdRefs = Lenient.clipAll(brdRefs, 30, 100);
            dependsOn = Lenient.clipAll(dependsOn, 30, 300);
        }
    }
}
