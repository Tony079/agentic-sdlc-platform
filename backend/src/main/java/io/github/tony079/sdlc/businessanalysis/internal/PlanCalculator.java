package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * GD-34: the engine's part of the delivery plan (the model only proposes epics and stories). Pure functions,
 * unit-tested.
 * <ul>
 *   <li>{@link #prepare}: stable keys, unknown references dropped, each story's effort from the approved estimate's
 *       expected effort of the work items it carries (shared equally when a work item is split), flags.</li>
 *   <li>{@link #schedule}: dependency order (priority breaks ties, a cycle is broken and flagged), then, for a human
 *       team, sprints: each story is done by one person of its role, starts only after the stories it depends on are
 *       finished and, unless it is bigger than a sprint, fits inside one sprint; a pinned sprint (an Estimator's edit)
 *       is kept as set. GD-35, agent delivery: waves instead of sprints (stories whose dependencies are all in earlier
 *       waves are built in parallel by coding agents, then reviewed by people); outside work is listed apart.</li>
 * </ul>
 */
final class PlanCalculator {

    private PlanCalculator() {
    }

    record PreparedEpic(String epicKey, String title, String goal, List<String> designRefs) {
    }

    record PreparedStory(String storyKey, String epicKey, String title, List<String> acceptanceCriteria,
                         List<String> estimateRefs, List<String> brdRefs, String role, String priority,
                         List<String> dependsOn, double effortDays, List<String> flags, double agentHours,
                         boolean external, Double leadDays) {
    }

    record Prepared(List<PreparedEpic> epics, List<PreparedStory> stories) {
    }

    /**
     * One estimate work item as approved: its key, role and expected effort (adjustments included); in agent mode
     * also the agent's hours, whether it is outside work and its calendar wait.
     */
    record WorkItem(String key, String role, double expectedDays, double agentHours, boolean external, double leadDays) {
        WorkItem(String key, String role, double expectedDays) {
            this(key, role, expectedDays, 0, false, 0);
        }
    }

    /**
     * Sprint length in weeks, first sprint start, working days per week and people per role; GD-35: the delivery mode
     * (AGENT: the team are reviewers, agentSlots coding agents work in parallel).
     */
    record Settings(int sprintWeeks, LocalDate startDate, int daysPerWeek, Map<String, Integer> team,
                    String deliveryMode, int agentSlots) {
        Settings(int sprintWeeks, LocalDate startDate, int daysPerWeek, Map<String, Integer> team) {
            this(sprintWeeks, startDate, daysPerWeek, team, "HUMAN", 1);
        }

        boolean agentMode() {
            return EstimateCalculator.AGENT.equals(deliveryMode);
        }
    }

    /** A story's schedulable form, after edits. */
    record Schedulable(String storyKey, String title, String role, String priority, double effortDays,
                       List<String> dependsOn, Integer pinnedSprint, int ordinal, double agentHours, boolean external,
                       double leadDays) {
        Schedulable(String storyKey, String title, String role, String priority, double effortDays,
                    List<String> dependsOn, Integer pinnedSprint, int ordinal) {
            this(storyKey, title, role, priority, effortDays, dependsOn, pinnedSprint, ordinal, 0, false, 0);
        }
    }

    /**
     * GD-35: one wave of agent delivery. Wave 0 is outside work (people or suppliers), started at once. Days are
     * working days: agents build (their hours as working days, up to agentSlots in parallel), then people review.
     */
    record WaveLoad(int number, LocalDate start, LocalDate end, int stories, double agentHours, double buildDays,
                    double reviewDays, Map<String, Double> reviewByRole, double waitDays) {
    }

    record SprintLoad(int number, LocalDate start, LocalDate end, Map<String, Double> load,
                      Map<String, Double> capacity, boolean overCapacity) {
    }

    record Schedule(Map<String, Integer> sprintOf, List<String> order, List<SprintLoad> sprints,
                    Map<String, List<String>> flags, List<String> notes, List<WaveLoad> waves) {
    }

    /** GD-35: more agent hours than this in one story is flagged: too big for one agent task. */
    static final double LARGE_AGENT_HOURS = 16;

    static Prepared prepare(PlanContracts.Proposal proposal, List<WorkItem> estimate, Set<String> designKeys,
                            Set<String> brdKeys) {
        Map<String, Integer> used = new HashMap<>();
        List<PreparedEpic> epics = new ArrayList<>();
        Map<String, String> epicByTitle = new HashMap<>();
        for (PlanContracts.Epic e : proposal.epics()) {
            String key = key("epic", e.title(), used);
            epics.add(new PreparedEpic(key, e.title().strip(), e.goal().strip(),
                    e.designRefs().stream().map(String::strip).filter(designKeys::contains).distinct().toList()));
            epicByTitle.putIfAbsent(norm(e.title()), key);
        }
        Map<String, WorkItem> work = new HashMap<>();
        estimate.forEach(w -> work.put(w.key(), w));
        Map<String, Integer> shares = new HashMap<>();
        for (PlanContracts.Story s : proposal.stories()) {
            s.estimateRefs().stream().map(String::strip).filter(work::containsKey).distinct()
                    .forEach(k -> shares.merge(k, 1, Integer::sum));
        }
        if (epics.isEmpty() && !proposal.stories().isEmpty()) {
            String key = key("epic", "Delivery", used);
            epics.add(new PreparedEpic(key, "Delivery", "All planned work.", List.of()));
            epicByTitle.put(norm("Delivery"), key);
        }
        List<PreparedStory> stories = new ArrayList<>();
        for (PlanContracts.Story s : proposal.stories()) {
            List<String> flags = new ArrayList<>();
            String epicKey = epicByTitle.get(norm(s.epic()));
            if (epicKey == null) {
                epicKey = epics.getFirst().epicKey();
                flags.add("NO_EPIC");
            }
            List<String> refs = s.estimateRefs().stream().map(String::strip).filter(work::containsKey).distinct().toList();
            double effort = 0;
            double agentHours = 0;
            double lead = 0;
            boolean external = !refs.isEmpty();
            for (String k : refs) {
                WorkItem w = work.get(k);
                effort += w.expectedDays() / shares.get(k);
                agentHours += w.agentHours() / shares.get(k);
                external &= w.external();
                lead = Math.max(lead, w.leadDays());
            }
            if (refs.isEmpty()) {
                flags.add("NO_ESTIMATE");
            }
            if (s.acceptanceCriteria().isEmpty()) {
                flags.add("NO_CRITERIA");
            }
            stories.add(new PreparedStory(key("story", s.title(), used), epicKey, s.title().strip(),
                    s.acceptanceCriteria(), refs,
                    s.brdRefs().stream().map(String::strip).filter(brdKeys::contains).distinct().toList(),
                    s.role().name(), s.priority().name(), s.dependsOn(), EstimateCalculator.round(effort), flags,
                    EstimateCalculator.round(agentHours), external, external ? EstimateCalculator.round(lead) : null));
        }
        return new Prepared(epics, stories);
    }

    private static String key(String prefix, String title, Map<String, Integer> used) {
        String base = prefix + "-" + InputSets.sha256(norm(title)).substring(0, 12);
        int n = used.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + "-" + n;
    }

    private static String norm(String title) {
        return title == null ? "" : EvidenceVerifier.normalise(title).toLowerCase(Locale.ROOT);
    }

    private static int rank(String priority) {
        return switch (priority) {
            case "MUST" -> 0;
            case "SHOULD" -> 1;
            default -> 2;
        };
    }

    static double capacity(Settings settings, String role) {
        Integer people = settings.team() == null ? null : settings.team().get(role);
        int n = people == null || people < 1 ? 1 : people;
        return n * Math.max(1, settings.daysPerWeek()) * Math.max(1, settings.sprintWeeks()) * 1.0;
    }

    static Schedule schedule(List<Schedulable> stories, Settings settings) {
        Map<String, List<String>> flags = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Map<String, Schedulable> byTitle = new HashMap<>();
        Map<String, Schedulable> byKey = new LinkedHashMap<>();
        for (Schedulable s : stories) {
            byTitle.putIfAbsent(norm(s.title()), s);
            byKey.put(s.storyKey(), s);
        }
        // Dependency edges: dep -> dependants.
        Map<String, Set<String>> deps = new HashMap<>();
        Map<String, Set<String>> dependants = new HashMap<>();
        for (Schedulable s : stories) {
            Set<String> d = new LinkedHashSet<>();
            for (String t : s.dependsOn()) {
                Schedulable dep = byTitle.get(norm(t));
                if (dep != null && !dep.storyKey().equals(s.storyKey())) {
                    d.add(dep.storyKey());
                    dependants.computeIfAbsent(dep.storyKey(), k -> new LinkedHashSet<>()).add(s.storyKey());
                }
            }
            deps.put(s.storyKey(), d);
        }
        Comparator<Schedulable> byPriority = Comparator.comparingInt((Schedulable s) -> rank(s.priority()))
                .thenComparingInt(Schedulable::ordinal);
        Map<String, Integer> waiting = new HashMap<>();
        PriorityQueue<Schedulable> ready = new PriorityQueue<>(byPriority);
        for (Schedulable s : stories) {
            waiting.put(s.storyKey(), deps.get(s.storyKey()).size());
            if (deps.get(s.storyKey()).isEmpty()) {
                ready.add(s);
            }
        }
        List<String> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (order.size() < stories.size()) {
            if (ready.isEmpty()) {
                // A cycle: take the most important remaining story and flag the cycle.
                Schedulable next = stories.stream().filter(s -> !done.contains(s.storyKey())).min(byPriority).orElseThrow();
                flags.computeIfAbsent(next.storyKey(), k -> new ArrayList<>()).add("CYCLE");
                ready.add(next);
                waiting.put(next.storyKey(), 0);
            }
            Schedulable s = ready.poll();
            if (!done.add(s.storyKey())) {
                continue;
            }
            order.add(s.storyKey());
            for (String d : dependants.getOrDefault(s.storyKey(), Set.of())) {
                if (!done.contains(d) && waiting.merge(d, -1, Integer::sum) == 0) {
                    ready.add(byKey.get(d));
                }
            }
        }
        if (settings.agentMode()) {
            return waves(stories, settings, order, deps, byKey, flags, notes);
        }
        // Each story is done by one person of its role and starts once everything it depends on is finished (in
        // working days from the first sprint's start). A story that fits in a sprint is not split across two.
        double sprintDays = Math.max(1, settings.daysPerWeek()) * Math.max(1, settings.sprintWeeks());
        Map<String, double[]> people = new HashMap<>();
        Map<String, Double> finish = new HashMap<>();
        Map<String, Integer> sprintOf = new LinkedHashMap<>();
        Map<Integer, Map<String, Double>> load = new HashMap<>();
        Set<String> assumed = new LinkedHashSet<>();
        int last = 0;
        for (String key : order) {
            Schedulable s = byKey.get(key);
            if (settings.team() == null || settings.team().getOrDefault(s.role(), 0) < 1) {
                assumed.add(s.role());
            }
            double[] free = people.computeIfAbsent(s.role(), r -> new double[(int) Math.round(
                    capacity(settings, r) / sprintDays)]);
            int person = 0;
            for (int p = 1; p < free.length; p++) {
                if (free[p] < free[person]) {
                    person = p;
                }
            }
            double depsDone = 0;
            for (String d : deps.get(key)) {
                depsDone = Math.max(depsDone, finish.getOrDefault(d, 0.0));
            }
            boolean oversized = s.effortDays() > sprintDays + 1e-9;
            if (oversized) {
                flags.computeIfAbsent(key, k -> new ArrayList<>()).add("OVERSIZED");
            }
            double start;
            int sprint;
            if (s.pinnedSprint() != null && s.pinnedSprint() >= 1) {
                sprint = s.pinnedSprint();
                if (sprint < (int) Math.floor(depsDone / sprintDays + 1e-9) + 1) {
                    flags.computeIfAbsent(key, k -> new ArrayList<>()).add("BEFORE_DEPENDENCY");
                }
                start = Math.max((sprint - 1) * sprintDays, free[person]);
            } else {
                start = Math.max(depsDone, free[person]);
                double sprintStart = Math.floor(start / sprintDays + 1e-9) * sprintDays;
                boolean spills = start + s.effortDays() > sprintStart + sprintDays + 1e-9;
                if (start > sprintStart + 1e-9 && (spills || oversized)) {
                    start = sprintStart + sprintDays;
                }
                sprint = (int) Math.floor(start / sprintDays + 1e-9) + 1;
            }
            double end = start + s.effortDays();
            free[person] = end;
            finish.put(key, end);
            sprintOf.put(key, sprint);
            last = Math.max(last, Math.max(sprint, (int) Math.floor(Math.max(0, end - 1e-9) / sprintDays) + 1));
            load.computeIfAbsent(sprint, k -> new LinkedHashMap<>()).merge(s.role(), s.effortDays(), Double::sum);
        }
        assumed.forEach(r -> notes.add("No " + r.toLowerCase(Locale.ROOT) + " people in the team: one is assumed."));
        List<SprintLoad> sprints = new ArrayList<>();
        for (int n = 1; n <= last; n++) {
            Map<String, Double> l = load.getOrDefault(n, Map.of());
            Map<String, Double> c = new LinkedHashMap<>();
            boolean over = false;
            for (Map.Entry<String, Double> e : l.entrySet()) {
                double cap = capacity(settings, e.getKey());
                c.put(e.getKey(), cap);
                over |= e.getValue() > cap + 1e-9;
            }
            LocalDate start = settings.startDate().plusWeeks((long) (n - 1) * settings.sprintWeeks());
            sprints.add(new SprintLoad(n, start, start.plusWeeks(settings.sprintWeeks()).minusDays(1),
                    new LinkedHashMap<>(l), c, over));
        }
        return new Schedule(sprintOf, order, sprints, flags, notes, List.of());
    }

    /**
     * GD-35, agent delivery: wave n holds the stories whose dependencies are all in earlier waves; outside work is wave
     * 0. A wave starts when the previous one is accepted and every outside item its stories depend on has arrived. It
     * takes the agents' build time (hours as working days; up to agentSlots stories at once, at least the longest
     * story) plus the people's review time (per role, shared among its reviewers).
     */
    private static Schedule waves(List<Schedulable> stories, Settings settings, List<String> order,
                                  Map<String, Set<String>> deps, Map<String, Schedulable> byKey,
                                  Map<String, List<String>> flags, List<String> notes) {
        Map<String, Integer> waveOf = new LinkedHashMap<>();
        for (String key : order) {
            Schedulable s = byKey.get(key);
            if (s.external()) {
                waveOf.put(key, 0);
                continue;
            }
            int wave = 1;
            for (String d : deps.get(key)) {
                Integer w = waveOf.get(d);
                if (w != null && w > 0) {
                    wave = Math.max(wave, w + 1);
                }
            }
            waveOf.put(key, wave);
            if (s.agentHours() > LARGE_AGENT_HOURS + 1e-9) {
                flags.computeIfAbsent(key, k -> new ArrayList<>()).add("AGENT_OVERSIZED");
            }
        }
        int last = waveOf.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int slots = Math.max(1, settings.agentSlots());
        List<WaveLoad> waves = new ArrayList<>();
        double previousEnd = 0;
        double outsideEnd = 0;
        List<Schedulable> outside = stories.stream().filter(Schedulable::external).toList();
        if (!outside.isEmpty()) {
            Map<String, Double> review = new LinkedHashMap<>();
            outside.forEach(s -> review.merge(s.role(), s.effortDays(), Double::sum));
            outsideEnd = outside.stream().mapToDouble(s -> Math.max(s.leadDays(), s.effortDays())).max().orElse(0);
            waves.add(new WaveLoad(0, settings.startDate(), workingDate(settings, outsideEnd, true), outside.size(), 0, 0,
                    EstimateCalculator.round(review.values().stream().mapToDouble(Double::doubleValue).sum()), review,
                    EstimateCalculator.round(outsideEnd)));
            notes.add(outside.size() + " outside item(s) wait on people or suppliers: start them now (the longest wait is "
                    + EstimateCalculator.round(outsideEnd) + " working days).");
        }
        for (int n = 1; n <= last; n++) {
            final int wave = n;
            List<Schedulable> in = stories.stream().filter(s -> Integer.valueOf(wave).equals(waveOf.get(s.storyKey())))
                    .toList();
            double hours = in.stream().mapToDouble(Schedulable::agentHours).sum();
            double longest = in.stream().mapToDouble(Schedulable::agentHours).max().orElse(0);
            double build = Math.max(longest, hours / slots) / EstimateCalculator.AGENT_HOURS_PER_DAY;
            Map<String, Double> review = new LinkedHashMap<>();
            in.forEach(s -> review.merge(s.role(), s.effortDays(), Double::sum));
            double reviewDays = 0;
            for (Map.Entry<String, Double> e : review.entrySet()) {
                Integer people = settings.team() == null ? null : settings.team().get(e.getKey());
                reviewDays = Math.max(reviewDays, e.getValue() / (people == null || people < 1 ? 1 : people));
            }
            double wait = 0;
            for (Schedulable s : in) {
                for (String d : deps.get(s.storyKey())) {
                    Schedulable dep = byKey.get(d);
                    if (dep != null && dep.external()) {
                        wait = Math.max(wait, Math.max(dep.leadDays(), dep.effortDays()));
                    }
                }
            }
            double start = Math.max(previousEnd, wait);
            double end = start + build + reviewDays;
            waves.add(new WaveLoad(n, workingDate(settings, start, false), workingDate(settings, end, true), in.size(),
                    EstimateCalculator.round(hours), EstimateCalculator.round(build), EstimateCalculator.round(reviewDays),
                    review, EstimateCalculator.round(Math.max(0, wait - previousEnd))));
            previousEnd = end;
        }
        if (outsideEnd > previousEnd) {
            notes.add("Outside work takes longer than building: the plan ends when it arrives.");
        }
        return new Schedule(waveOf, order, List.of(), flags, notes, waves);
    }

    /**
     * The calendar date of a point in working days from the first day's start (Saturdays and Sundays skipped for a
     * 5-day week): for a start, the day work begins; for an end, the last day worked.
     */
    static LocalDate workingDate(Settings settings, double days, boolean end) {
        int whole = end ? Math.max(0, (int) Math.ceil(Math.max(0, days) - 1e-9) - 1)
                : (int) Math.floor(Math.max(0, days) + 1e-9);
        int perWeek = Math.max(1, Math.min(7, settings.daysPerWeek()));
        LocalDate date = settings.startDate();
        if (perWeek == 7) {
            return date.plusDays(whole);
        }
        int counted = 0;
        while (counted < whole) {
            date = date.plusDays(1);
            int dow = date.getDayOfWeek().getValue();
            if (perWeek <= 5 ? dow <= 5 : dow <= 6) {
                counted++;
            }
        }
        return date;
    }
}
