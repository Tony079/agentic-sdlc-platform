package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.tony079.sdlc.businessanalysis.internal.EstimateCalculator.Figures;
import io.github.tony079.sdlc.businessanalysis.internal.EstimateCalculator.Settings;
import io.github.tony079.sdlc.businessanalysis.internal.EstimateContracts.Item;
import io.github.tony079.sdlc.businessanalysis.internal.EstimateContracts.Phase;
import io.github.tony079.sdlc.businessanalysis.internal.EstimateContracts.Role;

import org.junit.jupiter.api.Test;

/** GD-33: the engine's arithmetic (the model never computes totals, timelines or cost). */
class EstimateCalculatorTest {

    private static Item item(String title, double low, double likely, double high, List<String> refs) {
        return new Item(title, "Detail.", Role.BACKEND, Phase.BUILD, refs, low, likely, high, List.of(), null, List.of());
    }

    @Test
    void figuresAreOrderedClippedAndFlagged() {
        var prepared = EstimateCalculator.prepare(List.of(
                item("Swapped", 5, 3, 2, List.of("d-1", "unknown")),
                item("Too small and too big", 0.01, 2, 400, List.of()),
                item("Wide", 1, 2, 9, List.of()),
                item("Large", 10, 20, 30, List.of()),
                item("Fine", 2, 3, 4, List.of())), Set.of("d-1"));
        assertThat(prepared.get(0).low()).isEqualTo(2);
        assertThat(prepared.get(0).high()).isEqualTo(5);
        assertThat(prepared.get(0).flags()).contains("CLIPPED");
        assertThat(prepared.get(0).designRefs()).containsExactly("d-1");
        assertThat(prepared.get(1).low()).isEqualTo(EstimateCalculator.MIN_DAYS);
        assertThat(prepared.get(1).high()).isEqualTo(EstimateCalculator.MAX_DAYS);
        assertThat(prepared.get(2).flags()).containsExactly("UNCERTAIN");
        assertThat(prepared.get(3).flags()).contains("LARGE");
        assertThat(prepared.get(4).flags()).isEmpty();
        assertThat(prepared).extracting(EstimateCalculator.Prepared::itemKey).doesNotHaveDuplicates();
    }

    @Test
    void totalsTimelineAndCostComeFromPertAndTheSettings() {
        Figures api = new Figures("a", "BACKEND", "BUILD", 2, 3, 5, List.of(), "API");
        Figures ui = new Figures("b", "FRONTEND", "BUILD", 3, 5, 10, List.of("API"), "UI");
        Figures tests = new Figures("c", "QA", "TEST", 1, 2, 3, List.of("UI"), "Tests");
        Settings settings = new Settings(Map.of("BACKEND", 1, "FRONTEND", 2), 5, 10, "INR",
                Map.of("BACKEND", new BigDecimal("1000"), "FRONTEND", new BigDecimal("800")));
        var s = EstimateCalculator.summarise(List.of(api, ui, tests), settings);
        double e = (2 + 12 + 5) / 6.0 + (3 + 20 + 10) / 6.0 + (1 + 8 + 3) / 6.0;
        assertThat(s.effort().p50()).isCloseTo(e, within(1e-9));
        assertThat(s.effort().p85()).isGreaterThan(s.effort().p50());
        assertThat(s.effortWithContingency().p50()).isCloseTo(e * 1.1, within(1e-9));
        // QA has nobody in the team: one person is assumed, and QA has no rate, so its cost is left out.
        assertThat(s.notes()).anyMatch(n -> n.contains("qa people")).anyMatch(n -> n.contains("No day rate for qa"));
        // The chain API -> UI -> Tests (in sequence) is longer than any one role's share of the work.
        assertThat(s.criticalPathWeeks()).isCloseTo(e * 1.1 / 5, within(1e-9));
        assertThat(s.weeks().p50()).isCloseTo(e * 1.1 / 5, within(1e-9));
        assertThat(s.costP50().doubleValue()).isCloseTo(1000 * (19 / 6.0) * 1.1 + 800 * (33 / 6.0) * 1.1, within(1.0));
        assertThat(s.currency()).isEqualTo("INR");
    }

    @Test
    void aDependencyCycleDoesNotLoopAndUnpricedSettingsGiveNoCost() {
        Figures a = new Figures("a", "BACKEND", "BUILD", 1, 1, 1, List.of("B"), "A");
        Figures b = new Figures("b", "BACKEND", "BUILD", 1, 1, 1, List.of("A"), "B");
        var s = EstimateCalculator.summarise(List.of(a, b), new Settings(Map.of("BACKEND", 1), 5, 0, null, null));
        assertThat(s.criticalPathWeeks()).isCloseTo(2 / 5.0, within(1e-9));
        assertThat(s.costP50()).isNull();
        assertThat(s.currency()).isNull();
    }

    @Test
    void spreadsheetCellsCannotStartAFormula() {
        assertThat(EstimateService.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(EstimateService.cell("+1")).startsWith("\"'+");
        assertThat(EstimateService.cell("Plain")).isEqualTo("\"Plain\"");
    }
}
