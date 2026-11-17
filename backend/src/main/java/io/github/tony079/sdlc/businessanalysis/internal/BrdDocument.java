package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.EvidenceView;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.ItemView;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.VersionView;

/**
 * The approved BRD as an exportable document: the stored version (immutable, identified by its content hash) with
 * the requirement it answers and its approval record. Both renderers (PDF, Word) draw from this one model, so the
 * two files always say the same thing. No source excerpts are included (AC-EXT-29).
 */
record BrdDocument(String organisation, String project, String requirementKey, String requirementTitle,
                   List<Field> requirement, int versionNo, String status, String contentHash, String producedAt,
                   String feasibility, List<String> feasibilityReasons, String summary, List<Section> sections,
                   List<String> excludedDocuments, List<Field> approval, String generatedAt, String generatedBy) {

    record Field(String label, String value) {
    }

    record Section(String title, List<Entry> entries) {
    }

    /** One BRD statement: its kind, the text, a details line and its evidence references. */
    record Entry(String kind, String statement, String details, List<String> evidence) {
    }

    /** Requirement header and approval facts read from the database for one version. */
    record Header(String organisation, String project, String requirementKey, String title, String description,
                  String businessObjective, String expectedOutcomes, String acceptanceCriteria,
                  String constraints) {
    }

    static final Map<String, String> SECTIONS = sectionTitles();

    private static Map<String, String> sectionTitles() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("CONTEXT", "Context");
        m.put("CURRENT_SYSTEM", "How the system works today");
        m.put("FACTS", "Facts");
        m.put("IMPACTED_COMPONENTS", "Impact");
        m.put("SCENARIOS", "Scenarios");
        m.put("RISKS", "Risks");
        m.put("ASSUMPTIONS", "Assumptions");
        m.put("NON_FUNCTIONAL", "Non-functional points");
        m.put("ACCEPTANCE_CRITERIA", "Acceptance criteria");
        m.put("MISSING_REQUIREMENTS", "Missing requirements");
        m.put("RECOMMENDATIONS", "Recommendations");
        m.put("QUESTIONS", "Open questions");
        m.put("UNSUPPORTED_REQUIREMENTS", "Requirements not traced to evidence");
        return m;
    }

    private static final Map<String, String> KINDS = Map.of("FACT", "Fact", "ASSUMPTION", "Assumption",
            "RECOMMENDATION", "Recommendation", "QUESTION", "Question");

    private static final Map<String, String> SUPPORT = Map.of("VERIFIED", "Evidence verified",
            "FLAGGED", "Evidence not verified; accepted as is by the author",
            "UNSUPPORTED", "No verified evidence");

    private static final Map<String, String> VERDICTS = Map.of("FEASIBLE", "Feasible",
            "FEASIBLE_WITH_RISKS", "Feasible with risks", "NOT_FEASIBLE", "Not feasible");

    static BrdDocument of(Header header, VersionView version, String feasibility, List<String> reasons,
                          List<ReviewRepository.ClientDecision> decisions, List<String> excludedDocuments,
                          String generatedBy, ZoneId zone) {
        DateTimeFormatter when = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH).withZone(zone);

        List<Field> requirement = new ArrayList<>();
        add(requirement, "Description", header.description());
        add(requirement, "Business objective", header.businessObjective());
        add(requirement, "Expected outcomes", header.expectedOutcomes());
        add(requirement, "Acceptance criteria (as submitted)", header.acceptanceCriteria());
        add(requirement, "Constraints", header.constraints());

        List<Section> sections = new ArrayList<>();
        for (Map.Entry<String, String> s : SECTIONS.entrySet()) {
            List<Entry> entries = version.items().stream().filter(i -> s.getKey().equals(i.section()))
                    .map(BrdDocument::entry).toList();
            if (!entries.isEmpty()) {
                sections.add(new Section(s.getValue(), entries));
            }
        }
        // Items in a section this template does not know are kept, never dropped.
        List<Entry> other = version.items().stream().filter(i -> !SECTIONS.containsKey(i.section()))
                .map(BrdDocument::entry).toList();
        if (!other.isEmpty()) {
            sections.add(new Section("Other statements", other));
        }

        List<Field> approval = new ArrayList<>();
        for (ReviewRepository.ClientDecision d : decisions) {
            switch (d.kind()) {
                case "AUTHOR_CONFIRMED" -> approval.add(new Field("Confirmed by the author",
                        d.decidedByName() + ", " + when.format(d.decidedAt())));
                case "APPROVED" -> approval.add(new Field("Approved by the BRD Approver",
                        d.decidedByName() + ", " + when.format(d.decidedAt())));
                case "FLAG_ACCEPTED" -> approval.add(new Field("Flagged statement accepted as is",
                        d.decidedByName() + ", " + when.format(d.decidedAt())));
                default -> {
                }
            }
        }
        approval.add(new Field("Content fingerprint (SHA-256)", version.contentHash()));

        return new BrdDocument(header.organisation(), header.project(), header.requirementKey(), header.title(),
                requirement, version.versionNo(),
                "RELEASED".equals(version.status()) ? "Approved" : "Approved, replaced by a later version",
                version.contentHash(), when.format(version.createdAt()),
                feasibility == null ? null : VERDICTS.getOrDefault(feasibility, feasibility), reasons,
                version.understanding(), sections, excludedDocuments, approval,
                when.format(OffsetDateTime.now()), generatedBy);
    }

    private static void add(List<Field> fields, String label, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(new Field(label, value.strip()));
        }
    }

    private static Entry entry(ItemView item) {
        List<String> details = new ArrayList<>();
        details.add(SUPPORT.getOrDefault(item.supportState(), item.supportState()));
        if (item.risk() != null && !item.risk().isBlank()) {
            details.add("Risk: " + item.risk());
        }
        if (item.component() != null && !item.component().isBlank()) {
            details.add("Component: " + item.component());
        }
        return new Entry(KINDS.getOrDefault(item.kind(), item.kind()), item.statement(), String.join(" · ", details),
                item.evidence().stream().map(BrdDocument::evidence).toList());
    }

    private static String evidence(EvidenceView e) {
        if ("REPOSITORY".equals(e.type())) {
            String sha = e.commitSha() == null ? "" : " @ " + e.commitSha().substring(0, Math.min(12, e.commitSha().length()));
            return e.path() + ", lines " + e.startLine() + "–" + e.endLine() + sha;
        }
        return (e.fileName() == null ? "document" : e.fileName())
                + (e.chunkLabel() == null ? "" : ", " + e.chunkLabel())
                + (e.chunk() == null ? "" : " (part " + e.chunk() + ")");
    }

    /** File name stem, e.g. REQ-000001-BRD-v2. */
    String fileStem() {
        return (requirementKey == null ? "BRD" : requirementKey.replaceAll("[^A-Za-z0-9-]", "")) + "-BRD-v" + versionNo;
    }
}
