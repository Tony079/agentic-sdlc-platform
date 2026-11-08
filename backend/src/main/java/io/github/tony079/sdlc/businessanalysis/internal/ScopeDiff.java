package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.tony079.sdlc.requirement.api.RequirementView.Content;

/**
 * Structured scope diff with priority (owner decision C-16, 2026-11-08). Compares the proposed scope with the approved
 * version field by field; each change is an ADDITION, MODIFICATION or REMOVAL in an area, with a deterministic
 * recommended priority:
 * <ul>
 *   <li>CRITICAL: any change touching security requirements (security terms in the old or new text), and removal of
 *       acceptance criteria;</li>
 *   <li>HIGH: business objective, acceptance criteria (added or modified), integrations;</li>
 *   <li>MEDIUM: functional description and expected outcomes, non-functional constraints;</li>
 *   <li>LOW: title wording.</li>
 * </ul>
 * The recommendation is advice: a Business Analyst may change it with a justification; nobody (and no model) approves or
 * rejects scope through this class.
 */
final class ScopeDiff {

    record Change(String field, String area, String changeType, String priority, String before, String after) {
    }

    private static final Pattern SECURITY = Pattern.compile(
            "(?i)\\b(security|secure|authenticat\\w*|authori[sz]\\w*|encrypt\\w*|password|credential\\w*|privacy|"
                    + "personal data|pii|gdpr|access control|audit|vulnerab\\w*|mfa|token)\\b");

    private ScopeDiff() {
    }

    static Map<String, String> fields(Content c) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("title", c.title());
        m.put("businessObjective", c.businessObjective());
        m.put("description", c.description());
        m.put("expectedOutcomes", c.expectedOutcomes());
        m.put("acceptanceCriteria", c.acceptanceCriteria());
        m.put("integrations", c.integrations());
        m.put("constraints", c.constraints());
        return m;
    }

    static List<Change> compare(Content approved, Content proposed) {
        Map<String, String> before = fields(approved);
        Map<String, String> after = fields(proposed);
        List<Change> changes = new ArrayList<>();
        for (String field : before.keySet()) {
            String b = blank(before.get(field));
            String a = blank(after.get(field));
            if (Objects.equals(b, a)) {
                continue;
            }
            String type = b == null ? "ADDITION" : a == null ? "REMOVAL" : "MODIFICATION";
            boolean security = (b != null && SECURITY.matcher(b).find()) || (a != null && SECURITY.matcher(a).find());
            String area = security ? "SECURITY" : area(field);
            changes.add(new Change(field, area, type, priority(field, area, type), b, a));
        }
        return changes;
    }

    private static String area(String field) {
        return switch (field) {
            case "businessObjective" -> "BUSINESS_OBJECTIVE";
            case "description", "expectedOutcomes" -> "FUNCTIONAL";
            case "acceptanceCriteria" -> "ACCEPTANCE_CRITERIA";
            case "integrations" -> "INTEGRATIONS";
            case "constraints" -> "NON_FUNCTIONAL";
            default -> "GENERAL";
        };
    }

    static String priority(String field, String area, String type) {
        if ("SECURITY".equals(area) || ("ACCEPTANCE_CRITERIA".equals(area) && "REMOVAL".equals(type))) {
            return "CRITICAL";
        }
        return switch (area) {
            case "BUSINESS_OBJECTIVE", "ACCEPTANCE_CRITERIA", "INTEGRATIONS" -> "HIGH";
            case "FUNCTIONAL", "NON_FUNCTIONAL" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static boolean validPriority(String value) {
        return value != null && List.of("CRITICAL", "HIGH", "MEDIUM", "LOW").contains(value.toUpperCase(Locale.ROOT));
    }
}
