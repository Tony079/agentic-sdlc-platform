package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.json.JsonMapper;

/**
 * GD-29 step B: watches the answer text while the model streams it and adds each finished statement (BA) or design
 * item title (Architect), and each question, to the timeline as a DRAFT line. The text is the model's own and not yet
 * checked; it is shown labelled as a draft, and only to people who may see the unapproved document. A repaired answer
 * starts again from the beginning; lines already shown are not repeated.
 */
final class DraftWatcher implements Consumer<String> {

    private static final Pattern SECTION = Pattern.compile("\"section\"\\s*:\\s*\"([A-Z_]+)\"");

    private final ActivityLog activity;
    /** Captured on the job's thread: the model gateway calls back on its own call thread, which has no tenant. */
    private final UUID tenant = io.github.tony079.sdlc.tenancy.api.TenantContext.requireTenant();
    private final UUID requirementId;
    private final ActivityLog.Stage stage;
    private final UUID run;
    private final Pattern field;
    private final Map<String, String> sections;
    private final JsonMapper json;
    private final Set<String> shown = new HashSet<>();
    private int scanned;
    private int lastLength;

    private DraftWatcher(ActivityLog activity, UUID requirementId, ActivityLog.Stage stage, UUID run, String fieldName,
                         Map<String, String> sections, JsonMapper json) {
        this.activity = activity;
        this.requirementId = requirementId;
        this.stage = stage;
        this.run = run;
        this.field = Pattern.compile("\"(" + fieldName + "|question)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        this.sections = sections;
        this.json = json;
    }

    /** BRD statements and questions. */
    static DraftWatcher brd(ActivityLog activity, UUID requirementId, UUID run, JsonMapper json) {
        Map<String, String> titles = new java.util.HashMap<>(BrdDocument.SECTIONS);
        return new DraftWatcher(activity, requirementId, ActivityLog.Stage.BRD, run, "statement", titles, json);
    }

    /** GD-33: estimate work item titles. */
    static DraftWatcher estimate(ActivityLog activity, UUID requirementId, UUID run, JsonMapper json) {
        return new DraftWatcher(activity, requirementId, ActivityLog.Stage.ESTIMATE, run, "title",
                Map.of("__none__", "Work item"), json);
    }

    /** Design item titles and open questions are not separate objects; titles are watched. */
    static DraftWatcher design(ActivityLog activity, UUID requirementId, UUID run, Map<String, String> sections,
                               JsonMapper json) {
        return new DraftWatcher(activity, requirementId, ActivityLog.Stage.DESIGN, run, "title", sections, json);
    }

    @Override
    public void accept(String text) {
        if (text.length() < lastLength) {
            scanned = 0; // a repair attempt: the answer starts again
        }
        lastLength = text.length();
        Matcher m = field.matcher(text);
        int from = Math.max(0, scanned - 1);
        while (m.find(from)) {
            from = m.end();
            scanned = m.end();
            String value = unescape(m.group(2));
            if (value == null || value.isBlank() || !shown.add(m.group(1) + ":" + value)) {
                continue;
            }
            String label;
            if ("question".equals(m.group(1))) {
                label = "Question";
            } else {
                int open = text.lastIndexOf('{', m.start());
                Matcher s = SECTION.matcher(text.substring(Math.max(0, open), m.start()));
                String section = s.find() ? s.group(1) : null;
                label = section == null ? "Draft" : sections.getOrDefault(section, section);
            }
            String line = label + ": " + value.strip();
            io.github.tony079.sdlc.tenancy.api.TenantContext.callAs(tenant, () -> {
                activity.add(requirementId, stage, run, ActivityLog.Kind.DRAFT, line);
                return null;
            });
        }
    }

    private String unescape(String raw) {
        try {
            return json.readValue("\"" + raw + "\"", String.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
