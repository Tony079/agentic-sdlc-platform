package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** GD-36: what the Developer agent returns at the end of one session of work on a story (prompt dev.story v1). */
final class DevContracts {

    private DevContracts() {
    }

    enum Status { CONTINUE, DONE, GIVE_UP }

    record Step(@NotNull Status status, @NotNull @Size(max = 4000) String summary,
                @NotNull @Size(max = 20) List<@Size(max = 300) String> testsRun, Boolean testsPassed,
                @Size(max = 2000) String nextSteps) {
        Step {
            summary = Lenient.clip(summary, 1500);
            testsRun = Lenient.clipAll(testsRun, 20, 300);
            nextSteps = Lenient.clip(nextSteps, 1000);
        }
    }
}
