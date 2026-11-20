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
 *   <li>{@link #schedule}: dependency order (priority breaks ties, a cycle is broken and flagged), then sprints packed by
 *       each role's capacity; a pinned sprint (an Estimator's edit) is kept as set.</li>
 * </ul>
 */
final class PlanCalculator {

    private PlanCalculator() {
    }

    record PreparedEpic(String epicKey, String title, String goal, List<String> designRefs) {
    }

    record PreparedStory(String storyKey, String epicKey, String title, List<String> acceptanceCriteria,
                         List<String> estimateRefs, List<String> brdRefs, String role, String priority,
                         List<String> dependsOn, double effortDays, List<String> flags) {
    }

    record Prepared(List<PreparedEpic> epics, List<PreparedStory> stories) {
    }

    /** One estimate work item as approved: its key, role and expected effort (adjustments included). */
    record WorkItem(String key, String role, double expectedDays) {
    }

    /** Sprint length in weeks, first sprint start, working days per week and people per role. */
    record Settings(int sprintWeeks, LocalDate startDate, int daysPerWeek, Map<String, Integer> team) {
    }

    /** A story's schedulable form, after edits. */
    record Schedulable(String storyKey, String title, String role, String priority, double effortDays,
                       List<String> dependsOn, Integer pinnedSprint, int ordinal) {
    }

    record SprintLoad(int number, LocalDate start, LocalDate end, Map<String, Double> load,
                      Map<String, Double> capacity, boolean overCapacity) {
    }

    record Schedule(Map<String, Integer> sprintOf, List<String> order, List<SprintLoad> sprints,
                    Map<String, List<String>> flags, List<String> notes) {
    }

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
            for (String k : refs) {
                effort += work.get(k).expectedDays() / shares.get(k);
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
                    s.role().name(), s.priority().name(), s.dependsOn(), EstimateCalculator.round(effort), flags));
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
        // Pack into sprints by role capacity; a story never goes before a story it depends on.
        Map<String, Integer> sprintOf = new LinkedHashMap<>();
        Map<Integer, Map<String, Double>> load = new HashMap<>();
        Set<String> assumed = new LinkedHashSet<>();
        for (String key : order) {
            Schedulable s = byKey.get(key);
            if (settings.team() == null || settings.team().getOrDefault(s.role(), 0) < 1) {
                assumed.add(s.role());
            }
            double cap = capacity(settings, s.role());
            int earliest = 1;
            for (String d : deps.get(key)) {
                Integer ds = sprintOf.get(d);
                if (ds != null) {
                    earliest = Math.max(earliest, ds);
                }
            }
            int sprint;
            if (s.pinnedSprint() != null && s.pinnedSprint() >= 1) {
                sprint = s.pinnedSprint();
                if (sprint < earliest) {
                    flags.computeIfAbsent(key, k -> new ArrayList<>()).add("BEFORE_DEPENDENCY");
                }
            } else if (s.effortDays() > cap) {
                flags.computeIfAbsent(key, k -> new ArrayList<>()).add("OVERSIZED");
                sprint = earliest;
                while (load.getOrDefault(sprint, Map.of()).getOrDefault(s.role(), 0.0) > 0) {
                    sprint++;
                }
            } else {
                sprint = earliest;
                while (load.getOrDefault(sprint, Map.of()).getOrDefault(s.role(), 0.0) + s.effortDays() > cap + 1e-9) {
                    sprint++;
                }
            }
            sprintOf.put(key, sprint);
            load.computeIfAbsent(sprint, k -> new LinkedHashMap<>()).merge(s.role(), s.effortDays(), Double::sum);
        }
        assumed.forEach(r -> notes.add("No " + r.toLowerCase(Locale.ROOT) + " people in the team: one is assumed."));
        int last = sprintOf.values().stream().mapToInt(Integer::intValue).max().orElse(0);
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
        return new Schedule(sprintOf, order, sprints, flags, notes);
    }
}
