package io.github.tony079.sdlc.businessanalysis.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GD-33: the arithmetic of an estimate, in the engine only (the model proposes figures, never totals). Pure functions,
 * unit-tested.
 * <ul>
 *   <li>{@link #prepare}: clips the agent's figures to sane ranges and flags clipped, very uncertain and very large
 *       items; gives each item a stable key.</li>
 *   <li>{@link #summarise}: expected effort per item (PERT), totals by role and phase with a P50 / P85 range,
 *       contingency, a team timeline (per role and along the dependency chain) and, with day rates, cost.</li>
 * </ul>
 */
final class EstimateCalculator {

    static final double MIN_DAYS = 0.25;
    static final double MAX_DAYS = 60;
    /** High more than this many times low is flagged UNCERTAIN. */
    static final double UNCERTAIN_RATIO = 3;
    /** Likely above this many days is flagged LARGE (should usually be split). */
    static final double LARGE_DAYS = 15;
    /** z for the 85th percentile of a normal distribution. */
    static final double Z85 = 1.036;

    private EstimateCalculator() {
    }

    /** A work item as stored (figures as proposed after clipping). */
    record Prepared(String itemKey, String title, String detail, String role, String phase, List<String> designRefs,
                    double low, double likely, double high, List<String> assumptions, String risk,
                    List<String> dependsOn, List<String> flags) {
    }

    /** Team, calendar, contingency and optional day rates (validated by the service). */
    record Settings(Map<String, Integer> team, int daysPerWeek, int contingencyPercent, String currency,
                    Map<String, BigDecimal> dayRates) {

        boolean priced() {
            return currency != null && dayRates != null && !dayRates.isEmpty();
        }
    }

    /** Figures in effect for an item (adjusted or as proposed). */
    record Figures(String itemKey, String role, String phase, double low, double likely, double high,
                   List<String> dependsOnTitles, String title) {

        double expected() {
            return (low + 4 * likely + high) / 6;
        }

        double spread() {
            return (high - low) / 6;
        }
    }

    record Range(double p50, double p85) {
    }

    record RoleLine(String role, double effortP50, double effortP85, int people, boolean assumedPerson,
                    double weeksP50, double weeksP85, BigDecimal costP50, BigDecimal costP85) {
    }

    record Summary(Range effort, Range effortWithContingency, Map<String, Range> byPhase, List<RoleLine> byRole,
                   Range weeks, double criticalPathWeeks, BigDecimal costP50, BigDecimal costP85, String currency,
                   int contingencyPercent, List<String> notes) {
    }

    static List<Prepared> prepare(List<EstimateContracts.Item> items, Set<String> designKeys) {
        List<Prepared> out = new ArrayList<>();
        Map<String, Integer> used = new HashMap<>();
        for (EstimateContracts.Item item : items) {
            List<String> flags = new ArrayList<>();
            double low = item.lowDays();
            double likely = item.likelyDays();
            double high = item.highDays();
            double[] sorted = {low, likely, high};
            java.util.Arrays.sort(sorted);
            boolean clipped = sorted[0] != low || sorted[1] != likely || sorted[2] != high;
            low = clamp(sorted[0]);
            likely = clamp(sorted[1]);
            high = clamp(sorted[2]);
            clipped |= low != sorted[0] || likely != sorted[1] || high != sorted[2];
            if (clipped) {
                flags.add("CLIPPED");
            }
            if (high > UNCERTAIN_RATIO * low) {
                flags.add("UNCERTAIN");
            }
            if (likely > LARGE_DAYS) {
                flags.add("LARGE");
            }
            List<String> refs = item.designRefs().stream().map(String::strip).filter(designKeys::contains).distinct()
                    .toList();
            out.add(new Prepared(key(item.title(), used), item.title().strip(), item.detail().strip(), item.role().name(),
                    item.phase().name(), refs, round(low), round(likely), round(high), item.assumptions(),
                    item.risk() == null || item.risk().isBlank() ? null : item.risk().strip(), item.dependsOn(), flags));
        }
        return out;
    }

    private static double clamp(double days) {
        if (Double.isNaN(days) || days < MIN_DAYS) {
            return MIN_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    /** Two decimals, as stored. */
    static double round(double days) {
        return BigDecimal.valueOf(days).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String key(String title, Map<String, Integer> used) {
        String base = "est-" + InputSets.sha256(EvidenceVerifier.normalise(title).toLowerCase(Locale.ROOT)).substring(0, 12);
        int n = used.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + "-" + n;
    }

    static Summary summarise(List<Figures> items, Settings settings) {
        List<String> notes = new ArrayList<>();
        double factor = 1 + settings.contingencyPercent() / 100.0;
        double expected = 0;
        double variance = 0;
        Map<String, double[]> phase = new LinkedHashMap<>();
        Map<String, double[]> role = new LinkedHashMap<>();
        for (Figures f : items) {
            expected += f.expected();
            variance += f.spread() * f.spread();
            phase.computeIfAbsent(f.phase(), k -> new double[2]);
            phase.get(f.phase())[0] += f.expected();
            phase.get(f.phase())[1] += f.spread() * f.spread();
            role.computeIfAbsent(f.role(), k -> new double[2]);
            role.get(f.role())[0] += f.expected();
            role.get(f.role())[1] += f.spread() * f.spread();
        }
        Range effort = range(expected, variance);
        Range withContingency = new Range(effort.p50() * factor, effort.p85() * factor);
        Map<String, Range> byPhase = new LinkedHashMap<>();
        phase.forEach((k, v) -> byPhase.put(k, range(v[0], v[1])));
        List<RoleLine> byRole = new ArrayList<>();
        BigDecimal costP50 = settings.priced() ? BigDecimal.ZERO : null;
        BigDecimal costP85 = settings.priced() ? BigDecimal.ZERO : null;
        double weeksP50 = 0;
        double weeksP85 = 0;
        int days = Math.max(1, settings.daysPerWeek());
        for (Map.Entry<String, double[]> e : role.entrySet()) {
            Range r = range(e.getValue()[0], e.getValue()[1]);
            double p50 = r.p50() * factor;
            double p85 = r.p85() * factor;
            Integer people = settings.team() == null ? null : settings.team().get(e.getKey());
            boolean assumed = people == null || people < 1;
            int n = assumed ? 1 : people;
            if (assumed) {
                notes.add("No " + e.getKey().toLowerCase(Locale.ROOT) + " people in the team: the timeline assumes one.");
            }
            double w50 = p50 / (n * days);
            double w85 = p85 / (n * days);
            weeksP50 = Math.max(weeksP50, w50);
            weeksP85 = Math.max(weeksP85, w85);
            BigDecimal c50 = null;
            BigDecimal c85 = null;
            if (settings.priced()) {
                BigDecimal rate = settings.dayRates().get(e.getKey());
                if (rate == null) {
                    notes.add("No day rate for " + e.getKey().toLowerCase(Locale.ROOT) + ": its cost is not included.");
                } else {
                    c50 = rate.multiply(BigDecimal.valueOf(p50)).setScale(0, RoundingMode.HALF_UP);
                    c85 = rate.multiply(BigDecimal.valueOf(p85)).setScale(0, RoundingMode.HALF_UP);
                    costP50 = costP50.add(c50);
                    costP85 = costP85.add(c85);
                }
            }
            byRole.add(new RoleLine(e.getKey(), p50, p85, n, assumed, w50, w85, c50, c85));
        }
        double critical = criticalPathDays(items) * factor / days;
        if (critical > weeksP50) {
            notes.add("The longest chain of dependent work items sets the timeline, not team capacity.");
        }
        Range weeks = new Range(Math.max(weeksP50, critical), Math.max(weeksP85, critical));
        return new Summary(effort, withContingency, byPhase, byRole, weeks, critical, costP50, costP85,
                settings.priced() ? settings.currency() : null, settings.contingencyPercent(), notes);
    }

    private static Range range(double expected, double variance) {
        return new Range(expected, expected + Z85 * Math.sqrt(variance));
    }

    /**
     * The longest chain of work along "dependsOn" (by expected effort, one person per item): work that must happen
     * in sequence. Unknown titles are ignored; a cycle is broken where it closes.
     */
    static double criticalPathDays(List<Figures> items) {
        Map<String, Figures> byTitle = new HashMap<>();
        for (Figures f : items) {
            byTitle.putIfAbsent(norm(f.title()), f);
        }
        Map<String, Double> memo = new HashMap<>();
        double longest = 0;
        for (Figures f : items) {
            longest = Math.max(longest, chain(f, byTitle, memo, new HashSet<>()));
        }
        return longest;
    }

    private static double chain(Figures f, Map<String, Figures> byTitle, Map<String, Double> memo, Set<String> path) {
        Double known = memo.get(f.itemKey());
        if (known != null) {
            return known;
        }
        path.add(f.itemKey());
        double before = 0;
        for (String title : f.dependsOnTitles()) {
            Figures dep = byTitle.get(norm(title));
            if (dep != null && !path.contains(dep.itemKey())) {
                before = Math.max(before, chain(dep, byTitle, memo, path));
            }
        }
        path.remove(f.itemKey());
        double total = before + f.expected();
        memo.put(f.itemKey(), total);
        return total;
    }

    private static String norm(String title) {
        return title == null ? "" : EvidenceVerifier.normalise(title).toLowerCase(Locale.ROOT);
    }
}
