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
    /** GD-35, agent mode: review days below this are raised to it. */
    static final double MIN_REVIEW_DAYS = 0.1;
    static final double MAX_REVIEW_DAYS = 30;
    /** Review ranges are naturally wider in relative terms; flagged UNCERTAIN only above this ratio. */
    static final double UNCERTAIN_RATIO_AGENT = 5;
    /** More agent hours than this is flagged LARGE: too big for one agent task (one pull request). */
    static final double LARGE_AGENT_HOURS = 16;
    static final double MAX_AGENT_HOURS = 200;
    static final double MAX_LEAD_DAYS = 180;
    /** Agent working hours counted per working day (agents are supervised during working hours). */
    static final double AGENT_HOURS_PER_DAY = 8;
    static final String AGENT = "AGENT";

    private EstimateCalculator() {
    }

    /** A work item as stored (figures as proposed after clipping). */
    record Prepared(String itemKey, String title, String detail, String role, String phase, List<String> designRefs,
                    double low, double likely, double high, List<String> assumptions, String risk,
                    List<String> dependsOn, List<String> flags, String kind, Double agentHours, Double leadDays) {
    }

    /** Team, calendar, contingency and optional day rates (validated by the service). */
    record Settings(Map<String, Integer> team, int daysPerWeek, int contingencyPercent, String currency,
                    Map<String, BigDecimal> dayRates, String deliveryMode, int agentSlots, BigDecimal agentCostPerHour) {

        Settings(Map<String, Integer> team, int daysPerWeek, int contingencyPercent, String currency,
                 Map<String, BigDecimal> dayRates) {
            this(team, daysPerWeek, contingencyPercent, currency, dayRates, "HUMAN", 1, null);
        }

        /** GD-35: coding agents build, people (the team) review. */
        boolean agentMode() {
            return AGENT.equals(deliveryMode);
        }

        boolean priced() {
            return currency != null && dayRates != null && !dayRates.isEmpty();
        }
    }

    /** Figures in effect for an item (adjusted or as proposed). */
    record Figures(String itemKey, String role, String phase, double low, double likely, double high,
                   List<String> dependsOnTitles, String title, String kind, double agentHours, double leadDays) {

        Figures(String itemKey, String role, String phase, double low, double likely, double high,
                List<String> dependsOnTitles, String title) {
            this(itemKey, role, phase, low, likely, high, dependsOnTitles, title, "WORK", 0, 0);
        }

        boolean external() {
            return "EXTERNAL".equals(kind);
        }

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

    /**
     * @param deliveryMode HUMAN (effort is development) or AGENT (effort is people's review; agents build)
     * @param agentHours   AGENT: the coding agents' total working time
     * @param waves        AGENT: groups of work that can be built in parallel, one after another
     * @param external     AGENT: work outside the code (people or suppliers), and the longest calendar wait
     * @param aiCost       AGENT: agent hours times the configured cost per agent hour, when set
     */
    record Summary(Range effort, Range effortWithContingency, Map<String, Range> byPhase, List<RoleLine> byRole,
                   Range weeks, double criticalPathWeeks, BigDecimal costP50, BigDecimal costP85, String currency,
                   int contingencyPercent, List<String> notes, String deliveryMode, double agentHours, int waves,
                   int external, double longestLeadDays, BigDecimal aiCost) {
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
                    item.risk() == null || item.risk().isBlank() ? null : item.risk().strip(), item.dependsOn(), flags,
                    "WORK", null, null));
        }
        return out;
    }

    /**
     * GD-35, agent mode: review days are clipped to 0.1 to 30, agent hours to 0 to 200 (0 for EXTERNAL work), lead
     * days to 0 to 180 (EXTERNAL only). Flags: CLIPPED, UNCERTAIN (review high above 5 x low), LARGE (more than 16
     * agent hours: split it into smaller agent tasks).
     */
    static List<Prepared> prepareAgent(List<EstimateContracts.AgentItem> items, Set<String> designKeys) {
        List<Prepared> out = new ArrayList<>();
        Map<String, Integer> used = new HashMap<>();
        for (EstimateContracts.AgentItem item : items) {
            List<String> flags = new ArrayList<>();
            boolean external = item.kind() == EstimateContracts.Kind.EXTERNAL;
            double[] review = {item.reviewLowDays(), item.reviewLikelyDays(), item.reviewHighDays()};
            double[] sorted = review.clone();
            java.util.Arrays.sort(sorted);
            boolean clipped = !java.util.Arrays.equals(sorted, review);
            double[] clamped = new double[3];
            for (int i = 0; i < 3; i++) {
                clamped[i] = Double.isNaN(sorted[i]) || sorted[i] < MIN_REVIEW_DAYS ? MIN_REVIEW_DAYS
                        : Math.min(sorted[i], MAX_REVIEW_DAYS);
                clipped |= clamped[i] != sorted[i];
            }
            double hours = item.agentHours() == null || item.agentHours().isNaN() ? 0 : item.agentHours();
            double agentHours = external ? 0 : Math.max(0, Math.min(hours, MAX_AGENT_HOURS));
            clipped |= !external && agentHours != hours;
            Double lead = null;
            if (external) {
                double l = item.leadDays() == null || item.leadDays().isNaN() ? 0 : item.leadDays();
                lead = Math.max(0, Math.min(l, MAX_LEAD_DAYS));
                clipped |= lead != l;
            }
            if (clipped) {
                flags.add("CLIPPED");
            }
            if (clamped[2] > UNCERTAIN_RATIO_AGENT * clamped[0]) {
                flags.add("UNCERTAIN");
            }
            if (agentHours > LARGE_AGENT_HOURS) {
                flags.add("LARGE");
            }
            List<String> refs = item.designRefs().stream().map(String::strip).filter(designKeys::contains).distinct()
                    .toList();
            out.add(new Prepared(key(item.title(), used), item.title().strip(), item.detail().strip(), item.role().name(),
                    item.phase().name(), refs, round(clamped[0]), round(clamped[1]), round(clamped[2]),
                    item.assumptions(), item.risk() == null || item.risk().isBlank() ? null : item.risk().strip(),
                    item.dependsOn(), flags, item.kind().name(), round(agentHours), lead == null ? null : round(lead)));
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
        if (!settings.agentMode()) {
            double critical = criticalPathDays(items) * factor / days;
            if (critical > weeksP50) {
                notes.add("The longest chain of dependent work items sets the timeline, not team capacity.");
            }
            Range weeks = new Range(Math.max(weeksP50, critical), Math.max(weeksP85, critical));
            return new Summary(effort, withContingency, byPhase, byRole, weeks, critical, costP50, costP85,
                    settings.priced() ? settings.currency() : null, settings.contingencyPercent(), notes, "HUMAN", 0, 0,
                    0, 0, null);
        }
        // GD-35: agents build in parallel (up to agentSlots at once), people review; EXTERNAL work waits on others.
        double agentHours = 0;
        int external = 0;
        double longestLead = 0;
        for (Figures f : items) {
            agentHours += f.agentHours();
            if (f.external()) {
                external++;
                longestLead = Math.max(longestLead, f.leadDays());
            }
        }
        int slots = Math.max(1, settings.agentSlots());
        double agentWeeks = agentHours / (slots * AGENT_HOURS_PER_DAY) / days;
        double critical = criticalPathDays(items, true) * factor / days;
        double p50 = Math.max(Math.max(weeksP50, agentWeeks), critical);
        double p85 = Math.max(Math.max(weeksP85, agentWeeks), critical * (effort.p50() > 0
                ? effort.p85() / effort.p50() : 1));
        if (critical >= weeksP50 && critical >= agentWeeks) {
            notes.add("The longest chain of dependent work (agent time, review and outside waits) sets the timeline.");
        } else if (agentWeeks > weeksP50) {
            notes.add("The number of coding agents working in parallel (" + slots + ") sets the timeline.");
        } else {
            notes.add("People's review time sets the timeline: more reviewers would shorten it.");
        }
        if (external > 0) {
            notes.add(external + " outside item(s) wait on people or suppliers: start them now; the longest wait is "
                    + round(longestLead) + " days.");
        }
        BigDecimal aiCost = null;
        if (settings.agentCostPerHour() != null && settings.agentCostPerHour().signum() > 0) {
            aiCost = settings.agentCostPerHour().multiply(BigDecimal.valueOf(agentHours * factor))
                    .setScale(0, RoundingMode.HALF_UP);
            if (costP50 != null) {
                costP50 = costP50.add(aiCost);
                costP85 = costP85.add(aiCost);
            }
        }
        return new Summary(effort, withContingency, byPhase, byRole, new Range(p50, p85), critical, costP50, costP85,
                settings.priced() || aiCost != null ? settings.currency() : null, settings.contingencyPercent(), notes,
                AGENT, round(agentHours), waves(items), external, round(longestLead), aiCost);
    }

    /** GD-35: how many groups of WORK items must be built one after another (dependency layers). */
    static int waves(List<Figures> items) {
        Map<String, Figures> byTitle = new HashMap<>();
        for (Figures f : items) {
            byTitle.putIfAbsent(norm(f.title()), f);
        }
        Map<String, Integer> memo = new HashMap<>();
        int most = 0;
        for (Figures f : items) {
            if (!f.external()) {
                most = Math.max(most, layer(f, byTitle, memo, new HashSet<>()));
            }
        }
        return most;
    }

    private static int layer(Figures f, Map<String, Figures> byTitle, Map<String, Integer> memo, Set<String> path) {
        Integer known = memo.get(f.itemKey());
        if (known != null) {
            return known;
        }
        path.add(f.itemKey());
        int before = 0;
        for (String title : f.dependsOnTitles()) {
            Figures dep = byTitle.get(norm(title));
            if (dep != null && !dep.external() && !path.contains(dep.itemKey())) {
                before = Math.max(before, layer(dep, byTitle, memo, path));
            }
        }
        path.remove(f.itemKey());
        memo.put(f.itemKey(), before + 1);
        return before + 1;
    }

    private static Range range(double expected, double variance) {
        return new Range(expected, expected + Z85 * Math.sqrt(variance));
    }

    /**
     * The longest chain of work along "dependsOn" (by expected effort, one person per item): work that must happen
     * in sequence. Unknown titles are ignored; a cycle is broken where it closes.
     */
    static double criticalPathDays(List<Figures> items) {
        return criticalPathDays(items, false);
    }

    /**
     * As {@link #criticalPathDays(List)}; in agent mode an item takes its agent hours (as working days) plus its
     * expected review, and EXTERNAL work takes its calendar wait (or the person's effort, if longer).
     */
    static double criticalPathDays(List<Figures> items, boolean agentMode) {
        Map<String, Figures> byTitle = new HashMap<>();
        for (Figures f : items) {
            byTitle.putIfAbsent(norm(f.title()), f);
        }
        Map<String, Double> memo = new HashMap<>();
        double longest = 0;
        for (Figures f : items) {
            longest = Math.max(longest, chain(f, byTitle, memo, new HashSet<>(), agentMode));
        }
        return longest;
    }

    private static double duration(Figures f, boolean agentMode) {
        if (!agentMode) {
            return f.expected();
        }
        return f.external() ? Math.max(f.leadDays(), f.expected()) : f.agentHours() / AGENT_HOURS_PER_DAY + f.expected();
    }

    private static double chain(Figures f, Map<String, Figures> byTitle, Map<String, Double> memo, Set<String> path,
                                boolean agentMode) {
        Double known = memo.get(f.itemKey());
        if (known != null) {
            return known;
        }
        path.add(f.itemKey());
        double before = 0;
        for (String title : f.dependsOnTitles()) {
            Figures dep = byTitle.get(norm(title));
            if (dep != null && !path.contains(dep.itemKey())) {
                before = Math.max(before, chain(dep, byTitle, memo, path, agentMode));
            }
        }
        path.remove(f.itemKey());
        double total = before + duration(f, agentMode);
        memo.put(f.itemKey(), total);
        return total;
    }

    private static String norm(String title) {
        return title == null ? "" : EvidenceVerifier.normalise(title).toLowerCase(Locale.ROOT);
    }
}
