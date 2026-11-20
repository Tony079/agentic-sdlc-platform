package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GD-30: design items must be reviewable one point at a time, but models often bundle them. Before the checks, the
 * engine splits (deterministically, never rewording):
 * <ul>
 *   <li>an API item that lists several endpoints into one item per endpoint, titled "METHOD /path";</li>
 *   <li>a data-model item that lists several tables ("Name (id ... PK ...)") into one item per table;</li>
 *   <li>any item whose detail is a numbered list ("1. ... 2. ...") into one item per point.</li>
 * </ul>
 * Text that belongs to none of the parts (general rules after the list) is kept as its own item.
 */
final class DesignSplitter {

    private static final Pattern ENDPOINT = Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE)\\s+(/[^\\s,;]+)");
    private static final Pattern TABLE = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)");
    private static final Pattern KEYS = Pattern.compile("\\b(PK|FK|primary key|foreign key)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALSO_PATH = Pattern.compile("(?is)^(?:and|or)\\s+(/[^\\s,;]+)(.*)$");
    private static final Pattern SENTENCE = Pattern.compile("(?<=\\.)\\s+(?=[A-Z])");

    private DesignSplitter() {
    }

    static List<DesignContracts.Item> split(List<DesignContracts.Item> items) {
        List<DesignContracts.Item> out = new ArrayList<>();
        for (DesignContracts.Item item : items) {
            List<DesignContracts.Item> parts = switch (item.section()) {
                case API_CHANGES -> endpoints(item);
                case DATA_MODEL -> tables(item);
                default -> List.of();
            };
            if (parts.isEmpty()) {
                parts = numbered(item);
            }
            out.addAll(parts.isEmpty() ? List.of(item) : parts);
        }
        return out;
    }

    private static DesignContracts.Item copy(DesignContracts.Item item, String title, String detail) {
        return new DesignContracts.Item(item.section(), item.kind(), title, detail, item.component(), item.brdRefs(),
                item.evidence());
    }

    private static List<DesignContracts.Item> endpoints(DesignContracts.Item item) {
        String detail = item.detail();
        Matcher m = ENDPOINT.matcher(detail);
        List<int[]> at = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            at.add(new int[] {m.start(), m.end()});
            names.add(m.group(1) + " " + m.group(2).replaceAll("[.:]+$", ""));
        }
        if (at.size() < 2) {
            return List.of();
        }
        List<String> common = new ArrayList<>();
        String intro = detail.substring(0, at.get(0)[0]).strip();
        // A short lead-in ("On visitor origin,") applies to every endpoint; a longer one is a rule of its own.
        String lead = "";
        if (!intro.isEmpty() && !intro.endsWith(":")) {
            if (intro.split("\\s+").length <= 6) {
                lead = intro.replaceAll("[,;]$", "").strip() + ", ";
            } else {
                common.add(intro);
            }
        }
        String[] parts = new String[at.size()];
        for (int i = 0; i < at.size(); i++) {
            int end = i + 1 < at.size() ? at.get(i + 1)[0] : detail.length();
            String part = clean(detail.substring(at.get(i)[1], end));
            if (i == at.size() - 1) {
                // General rules after the last endpoint ("APIs secure with OAuth tokens ...") are not that endpoint's.
                String[] sentences = SENTENCE.split(part);
                part = sentences[0];
                for (int s = 1; s < sentences.length; s++) {
                    common.add(sentences[s].strip());
                }
            }
            part = clean(part.replaceFirst("^[\\s\\-–—:]+", ""));
            // "GET /a and GET /b take no body": the first has no description of its own.
            parts[i] = part.matches("(?i)^(and|or|,|&)?$") ? "" : part;
        }
        for (int i = parts.length - 2; i >= 0; i--) {
            if (parts[i].isEmpty()) {
                parts[i] = parts[i + 1];
            }
        }
        List<DesignContracts.Item> out = new ArrayList<>();
        for (int i = 0; i < at.size(); i++) {
            // "GET /s/{slug}/ and /s/{slug}/assets/{path} -> 200": the same method also serves a second path.
            Matcher also = ALSO_PATH.matcher(parts[i]);
            if (also.matches()) {
                names.set(i, names.get(i) + " and " + also.group(1));
                parts[i] = also.group(2).replaceFirst("^[\\s:,]+", "").strip();
            }
            String text = lead + names.get(i) + (parts[i].isEmpty() ? "" : ": " + parts[i]);
            out.add(copy(item, names.get(i), Character.toUpperCase(text.charAt(0)) + text.substring(1)));
        }
        if (!common.isEmpty()) {
            out.add(copy(item, item.title() + ": common rules", String.join(" ", common)));
        }
        return out;
    }

    private static List<DesignContracts.Item> tables(DesignContracts.Item item) {
        String detail = item.detail();
        Matcher m = TABLE.matcher(detail);
        List<DesignContracts.Item> out = new ArrayList<>();
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (!KEYS.matcher(m.group(2)).find()) {
                continue;
            }
            rest.append(detail, last, m.start()).append(' ');
            last = m.end();
            out.add(copy(item, "Table " + m.group(1), m.group(1) + " (" + m.group(2).strip() + ")."));
        }
        if (out.size() < 2) {
            return List.of();
        }
        rest.append(detail.substring(last));
        String other = rest.toString().replaceAll("(?i)^\\s*(key\\s+)?tables?\\s+(include|are)\\s*:", "")
                .replaceAll("\\s*;\\s*(;\\s*)+", "; ").replaceAll("^[\\s;,.]+", "").replaceAll("\\s+", " ").strip();
        if (other.split(" ").length >= 6) {
            out.add(copy(item, item.title() + ": other points", other));
        }
        return out;
    }

    private static List<DesignContracts.Item> numbered(DesignContracts.Item item) {
        List<String> parts = EvidenceVerifier.numberedParts(item.detail());
        if (parts.size() < 2) {
            return List.of();
        }
        List<DesignContracts.Item> out = new ArrayList<>();
        for (String part : parts) {
            // The original title keeps the context the list introduction gave ("Tests: template switching").
            out.add(copy(item, Lenient.clip(item.title() + ": " + shorten(part), 200), part));
        }
        return out;
    }

    private static String clean(String text) {
        return text.strip().replaceAll("[;,]\\s*(and)?\\s*$", "").strip();
    }

    /** A title from the start of the point: up to 90 characters, cut at a word. */
    static String shorten(String text) {
        String t = text.strip().replaceAll("\\.$", "");
        if (t.length() <= 90) {
            return t;
        }
        int cut = t.lastIndexOf(' ', 87);
        return t.substring(0, cut > 40 ? cut : 87) + "…";
    }
}
