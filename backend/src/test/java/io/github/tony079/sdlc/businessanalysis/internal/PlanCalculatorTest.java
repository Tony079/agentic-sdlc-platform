package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.tony079.sdlc.businessanalysis.internal.PlanCalculator.Schedulable;
import io.github.tony079.sdlc.businessanalysis.internal.PlanCalculator.Settings;
import io.github.tony079.sdlc.businessanalysis.internal.PlanCalculator.WorkItem;

import org.junit.jupiter.api.Test;

/** GD-34: effort from the approved estimate, dependency order and sprint packing, all in the engine. */
class PlanCalculatorTest {

    private static PlanContracts.Story story(String epic, String title, List<String> estimateRefs, List<String> deps) {
        return new PlanContracts.Story(epic, title, List.of("Criterion"), estimateRefs, List.of("brd-1", "unknown"),
                EstimateContracts.Role.BACKEND, PlanContracts.Priority.MUST, deps);
    }

    @Test
    void storyEffortComesFromTheEstimateAndSharedWorkIsSplit() {
        var proposal = new PlanContracts.Proposal(List.of(new PlanContracts.Epic("Core", "Goal.", List.of("d-1", "x"))),
                List.of(story("Core", "A", List.of("w1", "w2"), List.of()),
                        story("Core", "B", List.of("w2"), List.of()),
                        story("Missing epic", "C", List.of(), List.of())), List.of());
        var prepared = PlanCalculator.prepare(proposal, List.of(new WorkItem("w1", "BACKEND", 3),
                new WorkItem("w2", "BACKEND", 4)), Set.of("d-1"), Set.of("brd-1"));
        assertThat(prepared.epics().getFirst().designRefs()).containsExactly("d-1");
        assertThat(prepared.stories().get(0).effortDays()).isEqualTo(5.0);
        assertThat(prepared.stories().get(1).effortDays()).isEqualTo(2.0);
        assertThat(prepared.stories().get(0).brdRefs()).containsExactly("brd-1");
        assertThat(prepared.stories().get(2).flags()).contains("NO_EPIC", "NO_ESTIMATE");
        // The plan's total equals the estimate's expected effort.
        assertThat(prepared.stories().stream().mapToDouble(PlanCalculator.PreparedStory::effortDays).sum()).isEqualTo(7.0);
    }

    @Test
    void sprintsFollowCapacityDependenciesAndPriority() {
        Settings settings = new Settings(1, LocalDate.of(2026, 12, 7), 5, Map.of("BACKEND", 1));
        List<Schedulable> stories = List.of(
                new Schedulable("s1", "Base", "BACKEND", "MUST", 4, List.of(), null, 1),
                new Schedulable("s2", "Later", "BACKEND", "COULD", 1, List.of(), null, 2),
                new Schedulable("s3", "Needs base", "BACKEND", "MUST", 3, List.of("Base"), null, 3),
                new Schedulable("s4", "Huge", "BACKEND", "SHOULD", 9, List.of(), null, 4),
                new Schedulable("s5", "Pinned", "FRONTEND", "MUST", 1, List.of(), 4, 5));
        var schedule = PlanCalculator.schedule(stories, settings);
        // MUST before SHOULD before COULD, dependencies first.
        assertThat(schedule.order()).containsExactly("s1", "s3", "s5", "s4", "s2");
        assertThat(schedule.sprintOf()).containsEntry("s1", 1).containsEntry("s3", 2).containsEntry("s5", 4);
        assertThat(schedule.flags().get("s4")).containsExactly("OVERSIZED");
        assertThat(schedule.sprints().get(1).start()).isEqualTo(LocalDate.of(2026, 12, 14));
        assertThat(schedule.sprints().get(0).end()).isEqualTo(LocalDate.of(2026, 12, 13));
        assertThat(schedule.notes()).anyMatch(n -> n.contains("frontend"));
    }

    @Test
    void aDependencyCycleIsBrokenAndFlagged() {
        Settings settings = new Settings(2, LocalDate.of(2026, 12, 7), 5, Map.of("BACKEND", 2));
        var schedule = PlanCalculator.schedule(List.of(
                new Schedulable("a", "A", "BACKEND", "MUST", 1, List.of("B"), null, 1),
                new Schedulable("b", "B", "BACKEND", "MUST", 1, List.of("A"), null, 2)), settings);
        assertThat(schedule.order()).hasSize(2);
        assertThat(schedule.flags().values().stream().flatMap(List::stream)).contains("CYCLE");
        assertThat(PlanCalculator.capacity(settings, "BACKEND")).isCloseTo(20.0, within(1e-9));
    }
}
