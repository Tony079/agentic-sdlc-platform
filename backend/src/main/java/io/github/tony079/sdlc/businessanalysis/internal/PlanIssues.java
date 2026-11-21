package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GD-34 phase 2: how an approved delivery plan looks in GitHub Issues. The API freezes a {@link Payload} when a push is
 * requested; the worker renders each issue body from it, with the numbers of issues already pushed. Plan text (written
 * by the Planning agent, edited by Estimators) is neutralised so it cannot mention people (@name), link unrelated
 * issues (#12), or carry HTML.
 */
final class PlanIssues {

    static final int TITLE_MAX = 256;
    static final int LABEL_MAX = 50;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Pattern CHECKED = Pattern.compile("^- \\[[xX]\\] (.+)$", Pattern.MULTILINE);
    static final String GARUDA_LABEL = "garuda";

    record Payload(String requirementKey, String requirementTitle, int planVersionNo, List<Label> labels,
                   List<Milestone> milestones, List<Issue> issues) {
        int items() {
            return milestones.size() + issues.size();
        }
    }

    record Label(String name, String color, String description) {
    }

    record Milestone(String key, String title, String description, String dueOn) {
    }

    record Dependency(String key, String title) {
    }

    /**
     * @param sprint GD-35: in agent delivery, the wave (0: outside work)
     * @param agent  GD-35: built by a coding agent (agentHours) or outside work (leadDays); null for a human team
     */
    record Issue(String key, String title, String epic, String epicGoal, Integer sprint, String sprintDates,
                 String role, String priority, double effortDays, List<String> criteria, List<Dependency> dependsOn,
                 List<String> labels, String milestone, Agent agent) {
    }

    record Agent(double agentHours, boolean external, Double leadDays) {
    }

    private PlanIssues() {
    }

    // --- payload (API) ------------------------------------------------------------------------------------------

    /** The push content of an approved plan, from its project-visible view and the stories' original titles. */
    static Payload payload(String requirementKey, String requirementTitle, PlanService.VersionV view,
                           Map<String, String> keyByOriginalTitle) {
        Map<String, PlanService.EpicV> epics = new LinkedHashMap<>();
        view.epics().forEach(e -> epics.put(e.epicKey(), e));
        Map<String, String> keyByTitle = new LinkedHashMap<>(keyByOriginalTitle);
        Map<String, String> titleByKey = new LinkedHashMap<>();
        for (PlanService.StoryV s : view.stories()) {
            keyByTitle.putIfAbsent(s.title(), s.storyKey());
            titleByKey.put(s.storyKey(), s.title());
        }
        Map<String, Label> labels = new LinkedHashMap<>();
        labels.put(GARUDA_LABEL, new Label(GARUDA_LABEL, "0e8a16", "Created from a Garuda delivery plan"));
        boolean agentMode = EstimateCalculator.AGENT.equals(view.deliveryMode());
        List<Milestone> milestones = new ArrayList<>();
        Map<Integer, String> sprintDates = new LinkedHashMap<>();
        for (PlanService.WaveV w : view.waves()) {
            String dates = DAY.format(w.start()) + " to " + DAY.format(w.end());
            sprintDates.put(w.number(), dates);
            String name = w.number() == 0 ? "Outside work" : "Wave " + w.number();
            milestones.add(new Milestone("wave-" + w.number(), requirementKey + " " + name,
                    dates + ". " + (w.number() == 0 ? "Work outside the code (people or suppliers): start it now."
                            : "Built by coding agents in parallel, then reviewed by people.") + " Delivery plan version "
                            + view.versionNo() + " for " + requirementKey + " (" + text(requirementTitle, 200) + "), "
                            + stories(w.stories()) + ".", w.end().toString()));
        }
        for (PlanService.SprintV sp : view.sprints()) {
            String dates = DAY.format(sp.start()) + " to " + DAY.format(sp.end());
            sprintDates.put(sp.number(), dates);
            milestones.add(new Milestone("sprint-" + sp.number(), requirementKey + " Sprint " + sp.number(),
                    dates + ". Sprint " + sp.number() + " of delivery plan version " + view.versionNo() + " for "
                            + requirementKey + " (" + text(requirementTitle, 200) + "), "
                            + stories((int) view.stories().stream()
                                    .filter(s -> Integer.valueOf(sp.number()).equals(s.sprint())).count())
                            + ".", sp.end().toString()));
        }
        List<Issue> issues = new ArrayList<>();
        for (PlanService.StoryV s : view.stories()) {
            PlanService.EpicV epic = epics.get(s.epicKey());
            List<String> issueLabels = new ArrayList<>(List.of(GARUDA_LABEL));
            if (epic != null) {
                String name = label("epic: " + text(epic.title(), LABEL_MAX));
                labels.putIfAbsent(name, new Label(name, "5319e7", text("Epic: " + epic.goal(), 100)));
                issueLabels.add(name);
            }
            String role = EstimateService.ROLE_LABELS.getOrDefault(s.role(), s.role());
            String roleLabel = label("role: " + role.toLowerCase(Locale.ROOT));
            labels.putIfAbsent(roleLabel, new Label(roleLabel, "1d76db", role + " work"));
            issueLabels.add(roleLabel);
            String priorityLabel = "priority: " + s.priority().toLowerCase(Locale.ROOT);
            labels.putIfAbsent(priorityLabel, new Label(priorityLabel, switch (s.priority()) {
                case "MUST" -> "b60205";
                case "SHOULD" -> "fbca04";
                default -> "c2e0c6";
            }, "MoSCoW priority " + s.priority()));
            issueLabels.add(priorityLabel);
            List<Dependency> deps = new ArrayList<>();
            for (String d : s.dependsOn()) {
                String key = keyByTitle.get(d);
                deps.add(new Dependency(key, key != null && titleByKey.containsKey(key) ? titleByKey.get(key) : d));
            }
            if (agentMode) {
                String kindLabel = s.external() ? "outside-work" : "agent-ready";
                labels.putIfAbsent(kindLabel, s.external()
                        ? new Label(kindLabel, "d93f0b", "Waits on a person or supplier outside the code")
                        : new Label(kindLabel, "0e8a16", "Ready for a coding agent; a person reviews the pull request"));
                issueLabels.add(kindLabel);
            }
            issues.add(new Issue(s.storyKey(), s.title(), epic == null ? null : epic.title(),
                    epic == null ? null : epic.goal(), s.sprint(), s.sprint() == null ? null : sprintDates.get(s.sprint()),
                    role, s.priority(), s.effortDays(), s.acceptanceCriteria(), deps, issueLabels,
                    s.sprint() == null ? null : (agentMode ? "wave-" : "sprint-") + s.sprint(),
                    agentMode ? new Agent(s.agentHours(), s.external(), s.leadDays()) : null));
        }
        return new Payload(requirementKey, requirementTitle, view.versionNo(), List.copyOf(labels.values()), milestones,
                issues);
    }

    private static String stories(int n) {
        return n + (n == 1 ? " story" : " stories");
    }

    private static String label(String name) {
        return name.length() <= LABEL_MAX ? name : name.substring(0, LABEL_MAX - 1).strip() + "…";
    }

    // --- rendering (worker) -------------------------------------------------------------------------------------

    static String title(Issue issue) {
        return text(issue.title(), TITLE_MAX);
    }

    /**
     * The issue body. {@code numbers} holds the issue numbers already pushed (story key to number); {@code current} is
     * the body now on GitHub (null for a new issue): acceptance criteria ticked there stay ticked.
     */
    static String body(Payload payload, Issue issue, Map<String, Integer> numbers, String current) {
        Set<String> ticked = new HashSet<>();
        if (current != null) {
            Matcher m = CHECKED.matcher(current);
            while (m.find()) {
                ticked.add(m.group(1).strip());
            }
        }
        StringBuilder out = new StringBuilder();
        if (issue.epic() != null) {
            out.append("**Epic:** ").append(text(issue.epic(), 200)).append(" — ").append(text(issue.epicGoal(), 600))
                    .append("\n");
        }
        if (issue.sprint() != null) {
            out.append(issue.agent() == null ? "**Sprint:** " + issue.sprint()
                    : issue.sprint() == 0 ? "**Outside work**" : "**Wave:** " + issue.sprint());
            if (issue.sprintDates() != null) {
                out.append(" (").append(issue.sprintDates()).append(')');
            }
            out.append("\n");
        }
        if (issue.agent() == null) {
            out.append("**Role:** ").append(text(issue.role(), 40)).append(" · **Priority:** ").append(issue.priority())
                    .append(" · **Expected effort:** ").append(issue.effortDays()).append(" person-days\n\n");
        } else if (issue.agent().external()) {
            out.append("**Outside work:** a person or supplier, not a coding agent · **Expected wait:** ")
                    .append(issue.agent().leadDays() == null ? 0 : issue.agent().leadDays())
                    .append(" working days · **Owner effort:** ").append(issue.effortDays()).append(" person-days (")
                    .append(text(issue.role(), 40)).append(") · **Priority:** ").append(issue.priority())
                    .append("\n\n");
        } else {
            out.append("**Built by:** a coding agent (about ").append(issue.agent().agentHours())
                    .append(" agent hours) · **Reviewed by:** ").append(text(issue.role(), 40)).append(" (")
                    .append(issue.effortDays()).append(" person-days) · **Priority:** ").append(issue.priority())
                    .append("\n\n")
                    .append("**For the coding agent:** implement this story in one pull request that references this "
                            + "issue. The acceptance criteria below are the definition of done: add tests that show each "
                            + "one. A person reviews the pull request before it is merged.\n\n");
        }
        out.append("### Acceptance criteria\n\n");
        for (String c : issue.criteria()) {
            String line = text(c, 1000);
            out.append("- [").append(ticked.contains(line) ? 'x' : ' ').append("] ").append(line).append('\n');
        }
        if (!issue.dependsOn().isEmpty()) {
            out.append("\n### Depends on\n\n");
            for (Dependency d : issue.dependsOn()) {
                Integer n = d.key() == null ? null : numbers.get(d.key());
                out.append("- ").append(n == null ? "" : "#" + n + " ").append(text(d.title(), 300)).append('\n');
            }
        }
        out.append("\n---\n_Created by Garuda from delivery plan version ").append(payload.planVersionNo())
                .append(" of ").append(payload.requirementKey())
                .append(". Pushing the plan again updates the title, labels, milestone and this description; ticked "
                        + "acceptance criteria stay ticked._\n");
        return out.toString();
    }

    /**
     * Untrusted plan text, made inert: whitespace collapsed (no markdown structure), angle brackets escaped (no HTML),
     * a zero-width space after "@" and before digits after "#" (no mentions, no links to unrelated issues).
     */
    static String text(String value, int max) {
        if (value == null) {
            return "";
        }
        String s = value.replaceAll("\\s+", " ").strip()
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("@", "@​")
                .replaceAll("#(?=\\d)", "#​");
        return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
    }
}
