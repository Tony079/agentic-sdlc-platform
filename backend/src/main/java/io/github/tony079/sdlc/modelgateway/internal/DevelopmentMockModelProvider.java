package io.github.tony079.sdlc.modelgateway.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.tony079.sdlc.modelgateway.api.DataBlock;
import io.github.tony079.sdlc.modelgateway.api.ModelProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DEVELOPMENT MOCK, not a language model. It lets the local prototype run the whole analysis workflow without a
 * provider key (owner decision O-01 is open): it assembles a schema-valid reply for the Business Analyst prompts from
 * the call's own data blocks by fixed rules. No network, no randomness, no data leaves the process.
 *
 * <p>Nothing it returns may be mistaken for analysis:
 * <ul>
 *   <li>every statement, question and understanding starts with {@value #MARKER};</li>
 *   <li>the provider name {@value #NAME} is stored on every model call and BRD version, and the user interface labels
 *       such versions as mock output;</li>
 *   <li>usage is recorded as ESTIMATED;</li>
 *   <li>production mode refuses to start with it.</li>
 * </ul>
 *
 * <p>Behaviour: Phase A picks repository files by keyword overlap with the requirement. Phase B quotes one real line
 * of each supplied file and the start of each document chunk (exact quotes, so the platform's evidence verifier
 * checks them like any other citation), lists the acceptance criteria as untraced, and asks one fixed blocking
 * clarification until a clarification answer is among the inputs. Any other prompt gets an empty JSON object.
 * Selected only by {@code sdlc.model.provider=development-mock}.
 */
@Component
public class DevelopmentMockModelProvider implements ModelProvider {

    public static final String NAME = "development-mock";
    public static final String MODEL_ID = "development-mock-1";
    public static final String MARKER = "[DEVELOPMENT MOCK]";

    private static final int MAX_RELEVANT = 8;
    private static final int MAX_FILE_FACTS = 6;
    private static final int MAX_DOCUMENT_FACTS = 4;
    private static final int MAX_QUOTE = 200;
    private static final Pattern FILE_LABEL = Pattern.compile("^repository-file path=(.+) \\(line-numbered\\)$");
    private static final Pattern DOCUMENT_LABEL =
            Pattern.compile("^document documentVersionId=(\\S+) chunk=(\\d+) file=(.*?)(?: section=.*)?$");
    private static final Pattern NUMBERED_LINE = Pattern.compile("^(\\d+)\\| (.*)$");
    private static final Pattern WORD = Pattern.compile("[a-z]{4,}");
    private static final Set<String> STOPWORDS = Set.of("that", "this", "with", "from", "have", "must", "should",
            "will", "when", "then", "their", "there", "which", "were", "been", "into", "than", "them", "they",
            "what", "your", "title", "description", "business", "objective", "expected", "outcomes", "acceptance",
            "criteria", "constraints", "integrations", "user", "users", "able", "want", "need", "needs");
    private static final Set<String> SOURCE_SUFFIXES = Set.of(".java", ".kt", ".ts", ".tsx", ".js", ".jsx", ".py",
            ".go", ".rb", ".cs", ".php", ".scala", ".sql");

    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Result complete(Call call) {
        String text = switch (call.promptId()) {
            case "ba.phase-a" -> json.writeValueAsString(phaseA(call.data()));
            case "ba.phase-b" -> json.writeValueAsString(phaseB(call.data()));
            default -> "{}";
        };
        int input = call.instructions().length();
        for (DataBlock block : call.data()) {
            input += block.label().length() + block.content().length();
        }
        return new Result(text, new Usage((input + 3) / 4, (text.length() + 3) / 4, true));
    }

    // ---- Phase A ---------------------------------------------------------------------------------------------------

    private Map<String, Object> phaseA(List<DataBlock> data) {
        String requirement = content(data, "requirement");
        Set<String> keywords = keywords(requirement);
        List<String> paths = new ArrayList<>();
        for (String line : contentStartingWith(data, "repository:files").split("\n")) {
            String path = line.split("\t", 2)[0].strip();
            if (!path.isEmpty()) {
                paths.add(path);
            }
        }
        List<String> source = paths.stream().filter(DevelopmentMockModelProvider::isSource).toList();
        List<String> candidates = source.isEmpty() ? paths : source;
        List<String> relevant = candidates.stream()
                .sorted(Comparator.comparingInt((String p) -> -score(p, keywords)).thenComparing(Comparator.naturalOrder()))
                .limit(MAX_RELEVANT).toList();

        Map<String, List<String>> byComponent = new LinkedHashMap<>();
        for (String path : relevant) {
            byComponent.computeIfAbsent(componentOf(path), k -> new ArrayList<>()).add(path);
        }
        List<Map<String, Object>> components = new ArrayList<>();
        byComponent.forEach((name, files) -> components.add(Map.of("name", name, "paths", files)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("understanding", cut(MARKER + " No language model analysed this requirement. Requirement title: "
                + field(requirement, "Title") + ". The mock selected " + relevant.size() + " of " + paths.size()
                + " repository files by keyword match.", 2000));
        out.put("components", components);
        out.put("relevantPaths", relevant);
        return out;
    }

    // ---- Phase B ---------------------------------------------------------------------------------------------------

    private Map<String, Object> phaseB(List<DataBlock> data) {
        String requirement = content(data, "requirement");
        Set<String> keywords = keywords(requirement);
        List<Map<String, Object>> items = new ArrayList<>();

        int fileFacts = 0;
        int documentFacts = 0;
        for (DataBlock block : data) {
            Matcher file = FILE_LABEL.matcher(block.label());
            if (file.matches() && fileFacts < MAX_FILE_FACTS) {
                Map<String, Object> fact = fileFact(file.group(1), block.content(), keywords, fileFacts < 3);
                if (fact != null) {
                    items.add(fact);
                    fileFacts++;
                }
                continue;
            }
            Matcher document = DOCUMENT_LABEL.matcher(block.label());
            if (document.matches() && documentFacts < MAX_DOCUMENT_FACTS) {
                String quote = cut(block.content().strip().replaceAll("\\s+", " "), MAX_QUOTE);
                if (quote.replace(" ", "").length() >= 3) {
                    Map<String, Object> evidence = evidence("DOCUMENT", null, null, document.group(1),
                            Integer.valueOf(document.group(2)), quote);
                    items.add(item("FACT", "FACTS", MARKER + " The uploaded document \"" + document.group(3)
                            + "\" states: \"" + quote + "\"", List.of(evidence), null, null));
                    documentFacts++;
                }
            }
        }

        boolean answered = false;
        JsonNode notes = notes(contentStartingWith(data, "reviewer correction notes"));
        int noteItems = 0;
        for (JsonNode note : notes) {
            if ("CLARIFICATION".equals(note.path("source").asString(""))) {
                answered = true;
                String answer = note.path("answer").asString("");
                if (!answer.isBlank() && noteItems++ < 5) {
                    items.add(item("ASSUMPTION", "ASSUMPTIONS", MARKER + " Clarification answer used as input: \""
                            + cut(answer.strip(), 300) + "\"", List.of(), null, null));
                }
            } else if (!note.path("instruction").asString("").isBlank() && noteItems++ < 5) {
                items.add(item("RECOMMENDATION", "RECOMMENDATIONS", MARKER
                        + " Reviewer correction received for this version: \""
                        + cut(note.path("instruction").asString().strip(), 300) + "\"", List.of(), null, null));
            }
        }

        items.add(item("ASSUMPTION", "ASSUMPTIONS", MARKER + " This BRD was assembled from the inputs by fixed rules. "
                + "No language model analysed the requirement; every statement is a placeholder.", List.of(), null,
                null));
        items.add(item("ASSUMPTION", "RISKS", MARKER + " Impact, effort and risk have not been assessed.", List.of(),
                "Unassessed impact: do not plan work from this version.", null));
        items.add(item("RECOMMENDATION", "RECOMMENDATIONS", MARKER + " Configure a real model provider (owner "
                + "decision O-01) and run the analysis again before using this BRD.", List.of(), null, null));

        List<Map<String, Object>> unsupported = new ArrayList<>();
        String criteria = field(requirement, "Acceptance criteria");
        if (!criteria.isBlank()) {
            unsupported.add(Map.of("requirement", cut(MARKER + " Acceptance criteria not traced to evidence: "
                    + criteria, 1000)));
        }

        List<Map<String, Object>> clarifications = new ArrayList<>();
        if (!answered) {
            Map<String, Object> clarification = new LinkedHashMap<>();
            clarification.put("question", MARKER + " Which users or roles must be able to use this change?");
            clarification.put("whyItMatters", "The answer decides the permissions section of the BRD. The development "
                    + "mock asks this fixed question on the first run to exercise the clarification workflow.");
            clarification.put("interpretations", List.of("All existing users", "Only support staff or administrators"));
            clarification.put("recommendedOption", null);
            clarification.put("blocking", true);
            clarification.put("assignedTo", "CLIENT");
            clarifications.add(clarification);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("unsupported", unsupported);
        out.put("clarifications", clarifications);
        return out;
    }

    /** A FACT quoting one real line of the file: a keyword line, else a declaration, else the first substantial line. */
    private Map<String, Object> fileFact(String path, String numbered, Set<String> keywords, boolean impacted) {
        int chosen = 0;
        String chosenText = null;
        int rank = 0;
        for (String raw : numbered.split("\n")) {
            Matcher line = NUMBERED_LINE.matcher(raw);
            if (!line.matches()) {
                continue;
            }
            String text = line.group(2).strip();
            if (text.replace(" ", "").length() < 10) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            int lineRank = keywords.stream().anyMatch(lower::contains) && !lower.startsWith("import ")
                    && !lower.startsWith("package ") ? 3
                    : lower.contains("class ") || lower.contains("interface ") || lower.contains("def ")
                    || lower.contains("function ") ? 2 : 1;
            if (lineRank > rank) {
                rank = lineRank;
                chosen = Integer.parseInt(line.group(1));
                chosenText = text;
                if (rank == 3) {
                    break;
                }
            }
        }
        if (chosenText == null) {
            return null;
        }
        String quote = cut(chosenText, MAX_QUOTE);
        Map<String, Object> evidence = evidence("REPOSITORY", path, chosen, null, null, quote);
        String statement = MARKER + " " + path + " line " + chosen + " contains: \"" + quote + "\"";
        return item("FACT", impacted ? "IMPACTED_COMPONENTS" : "FACTS", statement, List.of(evidence), null,
                cut(componentOf(path), 200));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static Map<String, Object> item(String kind, String section, String statement,
                                            List<Map<String, Object>> evidence, String risk, String component) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", kind);
        item.put("section", section);
        item.put("statement", cut(statement, 2000));
        item.put("evidence", evidence);
        item.put("risk", risk);
        item.put("component", component);
        return item;
    }

    private static Map<String, Object> evidence(String type, String path, Integer line, String documentVersionId,
                                                Integer chunk, String quote) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("type", type);
        evidence.put("path", path);
        evidence.put("startLine", line);
        evidence.put("endLine", line);
        evidence.put("documentVersionId", documentVersionId);
        evidence.put("chunk", chunk);
        evidence.put("quote", quote);
        return evidence;
    }

    private JsonNode notes(String content) {
        if (content.isBlank()) {
            return json.createArrayNode();
        }
        try {
            JsonNode node = json.readTree(content);
            return node.isArray() ? node : json.createArrayNode();
        } catch (RuntimeException e) {
            return json.createArrayNode();
        }
    }

    private static String content(List<DataBlock> data, String label) {
        return data.stream().filter(b -> b.label().equals(label)).map(DataBlock::content).findFirst().orElse("");
    }

    private static String contentStartingWith(List<DataBlock> data, String prefix) {
        return data.stream().filter(b -> b.label().startsWith(prefix)).map(DataBlock::content).findFirst().orElse("");
    }

    /** The value of a "Label: value" line of the requirement block (first line of the value only). */
    private static String field(String requirement, String label) {
        for (String line : requirement.split("\n")) {
            if (line.startsWith(label + ": ")) {
                return line.substring(label.length() + 2).strip();
            }
        }
        return "";
    }

    private static Set<String> keywords(String text) {
        Set<String> words = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find() && words.size() < 40) {
            if (!STOPWORDS.contains(matcher.group())) {
                words.add(matcher.group());
            }
        }
        return words;
    }

    private static int score(String path, Set<String> keywords) {
        String lower = path.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            // Singular and plural forms of a requirement word both match a path ("refunds" matches RefundPolicy).
            if (lower.contains(keyword) || (keyword.endsWith("s") && lower.contains(keyword.substring(0,
                    keyword.length() - 1)))) {
                score++;
            }
        }
        return score;
    }

    private static boolean isSource(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && SOURCE_SUFFIXES.contains(path.substring(dot).toLowerCase(Locale.ROOT));
    }

    /** The file name without directories and extension. */
    private static String componentOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Cuts to a maximum length without splitting a surrogate pair. */
    private static String cut(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end).stripTrailing();
    }
}
