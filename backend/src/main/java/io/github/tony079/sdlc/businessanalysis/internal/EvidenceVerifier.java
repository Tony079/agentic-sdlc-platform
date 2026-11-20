package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Evidence;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.EvidenceType;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Item;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Kind;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Proposal;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Section;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Unsupported;

/**
 * Trusted citation verification (TDD 16.3, AC-CORE-04, AC-EXT-26/27/43). Pure code, never the model:
 * <ul>
 *   <li>Repository citation: the file exists in the pinned snapshot, the line range is inside it (at most 200 lines),
 *       the quoted text appears in those lines; the stored range hash is computed here from the snapshot text.</li>
 *   <li>Document citation: the version is in the frozen input set and was analysed, the chunk belongs to that
 *       extraction, the quoted text appears in the chunk.</li>
 *   <li>Support check (lexical): a key term of the statement appears in the cited text; otherwise the item is FLAGGED.</li>
 * </ul>
 * Invalid citations are removed (counted, never stored). A FACT without any valid citation becomes an ASSUMPTION.
 */
final class EvidenceVerifier {

    static final int MAX_RANGE_LINES = 200;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]{5,}");
    private static final Set<String> STOPWORDS = Set.of("about", "after", "again", "against", "being", "below",
            "between", "could", "every", "other", "should", "their", "there", "these", "those", "through", "under",
            "until", "which", "while", "would", "where", "requirement", "system", "client", "currently");

    record Chunk(int ordinal, String label, String text) {
    }

    /** One analysed document of the input set. */
    record DocumentSource(UUID versionId, int extractionNo, Map<Integer, Chunk> chunks) {
    }

    /** For a REQUIREMENT citation: path is the field key, chunkLabel its label, revisionNo the analysed revision. */
    record VerifiedEvidence(EvidenceType type, String path, Integer startLine, Integer endLine, String rangeSha256,
                            UUID documentVersionId, Integer extractionNo, Integer chunkOrdinal, String chunkLabel,
                            String quote, boolean supportPassed, Integer revisionNo) {

        VerifiedEvidence(EvidenceType type, String path, Integer startLine, Integer endLine, String rangeSha256,
                         UUID documentVersionId, Integer extractionNo, Integer chunkOrdinal, String chunkLabel,
                         String quote, boolean supportPassed) {
            this(type, path, startLine, endLine, rangeSha256, documentVersionId, extractionNo, chunkOrdinal, chunkLabel,
                    quote, supportPassed, null);
        }
    }

    /** The analysed revision of the client's requirement: its number and field text by key. */
    record RequirementSource(int revisionNo, Map<String, String> fields) {

        static final Map<String, String> LABELS = labels();

        private static Map<String, String> labels() {
            Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("title", "Title");
            m.put("description", "Description");
            m.put("businessObjective", "Business objective");
            m.put("expectedOutcomes", "Expected outcomes");
            m.put("acceptanceCriteria", "Acceptance criteria");
            m.put("constraints", "Constraints");
            m.put("integrations", "Integrations");
            m.put("productType", "Product type");
            return java.util.Collections.unmodifiableMap(m);
        }

        static RequirementSource of(int revisionNo, io.github.tony079.sdlc.requirement.api.RequirementView.Content c) {
            Map<String, String> f = new HashMap<>();
            put(f, "title", c.title());
            put(f, "description", c.description());
            put(f, "businessObjective", c.businessObjective());
            put(f, "expectedOutcomes", c.expectedOutcomes());
            put(f, "acceptanceCriteria", c.acceptanceCriteria());
            put(f, "constraints", c.constraints());
            put(f, "integrations", c.integrations());
            put(f, "productType", productType(c.kind()));
            return new RequirementSource(revisionNo, Map.copyOf(f));
        }

        /** Fixed text for the requirement's kind, so a statement about it can be cited and checked. */
        static String productType(String kind) {
            return "NEW_PRODUCT".equals(kind)
                    ? "New product: there is no existing system or code; the product is built from scratch."
                    : "Enhancement to an existing product with existing code.";
        }

        private static void put(Map<String, String> f, String key, String value) {
            if (value != null && !value.isBlank()) {
                f.put(key, value);
            }
        }
    }

    record VerifiedItem(int ordinal, String itemKey, Kind kind, Kind proposedKind, String section, String statement,
                        String risk, String component, String supportState, int rejectedCitations,
                        List<VerifiedEvidence> evidence) {
    }

    private EvidenceVerifier() {
    }

    /**
     * @param files     snapshot text of cited files that exist in the pinned snapshot (path to UTF-8 text)
     * @param documents analysed documents of the frozen input set, by version id
     */
    static List<VerifiedItem> verify(Proposal proposal, Map<String, String> files, Map<UUID, DocumentSource> documents) {
        return verify(proposal, files, documents, null);
    }

    /** @param requirement the analysed requirement revision, or null when requirement citations are not accepted */
    static List<VerifiedItem> verify(Proposal proposal, Map<String, String> files, Map<UUID, DocumentSource> documents,
                                     RequirementSource requirement) {
        List<VerifiedItem> out = new ArrayList<>();
        Map<String, Integer> keys = new HashMap<>();
        int ordinal = 0;
        for (Item item : splitNumbered(proposal.items())) {
            List<VerifiedEvidence> valid = new ArrayList<>();
            for (Evidence evidence : item.evidence()) {
                VerifiedEvidence checked = switch (evidence.type()) {
                    case REPOSITORY -> checkRepository(evidence, item.statement(), files);
                    case DOCUMENT -> checkDocument(evidence, item.statement(), documents);
                    case REQUIREMENT -> checkRequirement(evidence, item.statement(), requirement);
                };
                if (checked != null) {
                    valid.add(checked);
                }
            }
            int rejected = item.evidence().size() - valid.size();
            boolean anySupport = valid.stream().anyMatch(VerifiedEvidence::supportPassed);
            Kind kind = item.kind();
            String section = item.section().name();
            String state;
            if (valid.isEmpty()) {
                state = "UNSUPPORTED";
                if (kind == Kind.FACT) {
                    kind = Kind.ASSUMPTION;
                    if (item.section() == Section.FACTS) {
                        section = Section.ASSUMPTIONS.name();
                    }
                }
            } else {
                state = anySupport ? "VERIFIED" : "FLAGGED";
            }
            ordinal++;
            out.add(new VerifiedItem(ordinal, key(section, item.statement(), keys), kind, item.kind(), section,
                    item.statement().strip(), blankToNull(item.risk()), blankToNull(item.component()), state, rejected,
                    List.copyOf(valid)));
        }
        for (Unsupported unsupported : proposal.unsupported()) {
            ordinal++;
            String statement = unsupported.requirement().strip();
            out.add(new VerifiedItem(ordinal, key("UNSUPPORTED_REQUIREMENTS", statement, keys), Kind.ASSUMPTION,
                    Kind.ASSUMPTION, "UNSUPPORTED_REQUIREMENTS", statement, null, null, "UNSUPPORTED", 0, List.of()));
        }
        return out;
    }

    static VerifiedEvidence checkRepository(Evidence evidence, String statement, Map<String, String> files) {
        if (evidence.path() == null || evidence.startLine() == null || evidence.endLine() == null) {
            return null;
        }
        String text = files.get(evidence.path());
        if (text == null) {
            return null;
        }
        int start = evidence.startLine();
        int end = evidence.endLine();
        String[] lines = text.split("\n", -1);
        int lineCount = text.endsWith("\n") ? lines.length - 1 : lines.length;
        if (start < 1 || end < start || end > lineCount || end - start + 1 > MAX_RANGE_LINES) {
            return null;
        }
        String range = String.join("\n", java.util.Arrays.copyOfRange(lines, start - 1, end));
        if (!contains(range, evidence.quote())) {
            return null;
        }
        return new VerifiedEvidence(EvidenceType.REPOSITORY, evidence.path(), start, end, InputSets.sha256(range),
                null, null, null, null, evidence.quote().strip(), supports(statement, range));
    }

    static VerifiedEvidence checkDocument(Evidence evidence, String statement, Map<UUID, DocumentSource> documents) {
        if (evidence.documentVersionId() == null || evidence.chunk() == null) {
            return null;
        }
        UUID versionId;
        try {
            versionId = UUID.fromString(evidence.documentVersionId());
        } catch (IllegalArgumentException e) {
            return null;
        }
        DocumentSource source = documents.get(versionId);
        if (source == null) {
            return null;
        }
        Chunk chunk = source.chunks().get(evidence.chunk());
        if (chunk == null || !contains(chunk.text(), evidence.quote())) {
            return null;
        }
        return new VerifiedEvidence(EvidenceType.DOCUMENT, null, null, null, null, versionId, source.extractionNo(),
                chunk.ordinal(), chunk.label(), evidence.quote().strip(), supports(statement, chunk.text()));
    }

    /**
     * Requirement citation: the field exists in the analysed revision and the quote appears in it. Field names are
     * accepted in either form ("businessObjective" or "Business objective").
     */
    static VerifiedEvidence checkRequirement(Evidence evidence, String statement, RequirementSource requirement) {
        if (requirement == null || evidence.path() == null) {
            return null;
        }
        String key = fieldKey(evidence.path());
        String text = key == null ? null : requirement.fields().get(key);
        if (text == null || !looselyContains(text, evidence.quote())) {
            return null;
        }
        return new VerifiedEvidence(EvidenceType.REQUIREMENT, key, null, null, null, null, null, null,
                RequirementSource.LABELS.get(key), evidence.quote().strip(), supports(statement, text),
                requirement.revisionNo());
    }

    static String fieldKey(String name) {
        String n = name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        for (String key : RequirementSource.LABELS.keySet()) {
            if (key.toLowerCase(Locale.ROOT).equals(n)) {
                return key;
            }
        }
        return null;
    }

    /**
     * For the client's own requirement text: the quote's words appear in the same order, ignoring case and
     * punctuation (models often drop a full stop or change "React.js" capitalisation). At least three words.
     */
    static boolean looselyContains(String text, String quote) {
        String q = words(quote);
        return q.split(" ").length >= 3 && (" " + words(text) + " ").contains(" " + q + " ");
    }

    private static String words(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static final Pattern NUMBERED = Pattern.compile("(?:^|(?<=[\\s:;.]))(\\d{1,2})[.)]\\s+");

    /**
     * GD-30: a statement holding a numbered list ("1. ... 2. ...") becomes one item per point, so each acceptance
     * criterion, risk or scenario can be traced and reviewed on its own. An introduction ending in ":" is dropped.
     * Only a list numbered 1, 2, 3... (at least two points) is split; anything else is left as written.
     */
    static List<Item> splitNumbered(List<Item> items) {
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            List<String> parts = numberedParts(item.statement());
            if (parts.size() < 2) {
                out.add(item);
                continue;
            }
            for (String part : parts) {
                out.add(new Item(item.kind(), item.section(), part, item.evidence(), item.risk(), item.component()));
            }
        }
        return out;
    }

    static List<String> numberedParts(String statement) {
        var m = NUMBERED.matcher(statement);
        List<int[]> marks = new ArrayList<>();
        int expected = 1;
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) == expected) {
                marks.add(new int[] {m.start(), m.end()});
                expected++;
            }
        }
        if (marks.size() < 2) {
            return List.of(statement);
        }
        List<String> parts = new ArrayList<>();
        String intro = statement.substring(0, marks.get(0)[0]).strip();
        for (int i = 0; i < marks.size(); i++) {
            int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : statement.length();
            String part = statement.substring(marks.get(i)[1], end).strip()
                    .replaceAll("(?i)[;,]?\\s*(and|or)?\\s*$", "").strip();
            if (part.isEmpty()) {
                continue;
            }
            if (!intro.isEmpty() && !intro.endsWith(":")) {
                part = intro + " " + part;
            }
            parts.add(part.endsWith(".") ? part : part + ".");
        }
        return parts.isEmpty() ? List.of(statement) : parts;
    }

    /** The quote appears in the cited text, ignoring differences in whitespace; at least 3 visible characters. */
    static boolean contains(String text, String quote) {
        String q = normalise(quote);
        return q.replace(" ", "").length() >= 3 && normalise(text).contains(q);
    }

    /** Lexical support: some key term of the statement occurs in the cited text (or the statement has none). */
    static boolean supports(String statement, String cited) {
        String lowerCited = cited.toLowerCase(Locale.ROOT);
        var matcher = WORD.matcher(statement.toLowerCase(Locale.ROOT));
        boolean anyTerm = false;
        while (matcher.find()) {
            String term = matcher.group();
            if (STOPWORDS.contains(term)) {
                continue;
            }
            anyTerm = true;
            if (lowerCited.contains(term)) {
                return true;
            }
        }
        return !anyTerm;
    }

    static String normalise(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    /** Stable across versions for the same section and statement (TDD 16.1 item_key). */
    private static String key(String section, String statement, Map<String, Integer> used) {
        String base = section.toLowerCase(Locale.ROOT).replace('_', '-') + "-"
                + InputSets.sha256(normalise(statement).toLowerCase(Locale.ROOT)).substring(0, 12);
        int n = used.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + "-" + n;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
