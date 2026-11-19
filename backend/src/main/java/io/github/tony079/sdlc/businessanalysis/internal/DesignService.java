package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.ItemView;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.VersionView;
import io.github.tony079.sdlc.businessanalysis.internal.DesignRepository.DecisionRow;
import io.github.tony079.sdlc.businessanalysis.internal.DesignRepository.ItemRow;
import io.github.tony079.sdlc.businessanalysis.internal.DesignRepository.VersionRow;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.requirement.api.RequirementDirectory;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GD-26/GD-27: the Technical design stage. After BRD approval it belongs to the project's Technical Approvers: they
 * start it, decide flagged items and approve it (one may approve alone, after an explicit confirmation recorded on the
 * approval). The requirement's author follows it and may request changes. Until approved, only the author, BRD
 * Approvers and Technical Approvers see a design; other members see the approved one. The database enforces the same.
 */
@Service
@Profile("!migrate & !provision")
class DesignService {

    static final int AUTHOR_RETRIES = 3;
    /** BRD sections every design is expected to address (coverage). */
    static final Set<String> COVERED_SECTIONS = Set.of("SCENARIOS", "ACCEPTANCE_CRITERIA", "IMPACTED_COMPONENTS",
            "NON_FUNCTIONAL");

    record ItemV(String itemKey, int ordinal, String section, String kind, String title, String detail,
                 String component, List<String> brdRefs, String supportState, List<Map<String, Object>> evidence,
                 boolean accepted) {
    }

    record CoverageV(String itemKey, String section, String statement, boolean covered, List<String> coveredBy) {
    }

    record VersionV(UUID id, int versionNo, String status, int brdVersionNo, String summary, List<String> openQuestions,
                    String contentHash, String modelId, String changeRequest, String failureCode, int attempts,
                    OffsetDateTime createdAt, List<ItemV> items, List<CoverageV> coverage) {
    }

    record ReviewV(String stage, boolean isAuthor, boolean isTechnicalApprover, boolean canStart, boolean canRetry,
                   boolean canAcceptFlags, boolean canApprove, boolean canRequestChanges, List<String> unacceptedFlags,
                   String approvedBy, OffsetDateTime approvedAt, String approvalNote, boolean brdOutdated,
                   Integer currentBrdVersionNo, int technicalApprovers, String blockedReason) {
    }

    /** Recorded on every approval: the design was approved by one Technical Approver after an explicit confirmation. */
    static final String SINGLE_APPROVAL_NOTE = "Approved by a single Technical Approver after explicit confirmation; "
            + "no second reviewer.";

    record HistoryV(int versionNo, String status, String label, OffsetDateTime createdAt, String changeRequest,
                    String approvedBy, OffsetDateTime approvedAt, boolean downloadable) {
    }

    record DesignState(UUID requirementId, ReviewV review, VersionV version, List<HistoryV> history) {
    }

    private record Access(UUID me, UUID projectId, boolean author, boolean approver, boolean technical) {
        boolean insider() {
            return author || approver || technical;
        }
    }

    private final DesignRepository designs;
    private final ReviewRepository reviews;
    private final RequirementDirectory requirements;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final JsonMapper json;
    private final ActivityLog activity;

    DesignService(DesignRepository designs, ReviewRepository reviews, RequirementDirectory requirements,
                  OutboxPublisher outbox, AuditWriter audit, JsonMapper json, ActivityLog activity) {
        this.activity = activity;
        this.designs = designs;
        this.reviews = reviews;
        this.requirements = requirements;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
    }

    private Access access(UUID requirementId) {
        var visible = requirements.findVisible(requirementId).orElseThrow(ApiException::notFound);
        UUID me = TenantContext.currentActor().orElseThrow();
        return new Access(me, visible.projectId(), me.equals(visible.ownerId()), reviews.isBrdApprover(visible.projectId()),
                visible.technicalApprover());
    }

    // --- read ---------------------------------------------------------------------------------------------------

    /** GD-29: the agents' activity timeline for anyone who can see the requirement; lines after {@code after}. */
    @Transactional(readOnly = true)
    public List<ActivityLog.Entry> activity(UUID requirementId, long after) {
        requirements.findVisible(requirementId).orElseThrow(ApiException::notFound);
        return activity.list(requirementId, Math.max(0, after));
    }

    @Transactional
    public DesignState state(UUID requirementId) {
        Access a = access(requirementId);
        boolean insider = a.insider();
        var releasedBrd = reviews.latestWithStatus(requirementId, Set.of("RELEASED")).orElse(null);
        List<VersionRow> all = designs.versions(requirementId);
        VersionRow shown = insider ? all.stream().findFirst().orElse(null)
                : all.stream().filter(v -> "APPROVED".equals(v.status())).findFirst().orElse(null);
        List<DecisionRow> decisions = shown == null ? List.of() : designs.decisions(shown.id());
        DecisionRow approved = first(decisions, "APPROVED");
        VersionV version = shown == null ? null : versionView(shown, decisions);
        List<String> unaccepted = version == null ? List.of() : version.items().stream()
                .filter(i -> "FLAGGED".equals(i.supportState()) && !i.accepted()).map(ItemV::itemKey).toList();
        boolean inReview = shown != null && "IN_REVIEW".equals(shown.status());
        boolean open = all.stream().anyMatch(v -> Set.of("GENERATING", "IN_REVIEW", "FAILED").contains(v.status()));
        String stage = releasedBrd == null ? "BRD_NOT_APPROVED"
                : shown == null ? "NOT_STARTED"
                : switch (shown.status()) {
                    case "GENERATING" -> "GENERATING";
                    case "FAILED" -> "FAILED";
                    case "IN_REVIEW" -> "IN_REVIEW";
                    case "APPROVED" -> "APPROVED";
                    default -> "NOT_STARTED";
                };
        Integer brdNo = releasedBrd == null ? null : releasedBrd.versionNo();
        boolean outdated = shown != null && releasedBrd != null && !releasedBrd.id().equals(shown.brdVersionId());
        int technicalApprovers = designs.technicalApprovers(a.projectId()).size();
        String blocked = null;
        if (releasedBrd != null && technicalApprovers == 0) {
            blocked = "This project has no Technical Approver. Ask your Organisation Admin to assign one; the technical "
                    + "design starts once they do.";
        } else if (inReview && a.technical() && !unaccepted.isEmpty()) {
            blocked = unaccepted.size() + " flagged design item(s) need your decision before you approve.";
        }
        ReviewV review = new ReviewV(stage, a.author(), a.technical(),
                a.technical() && releasedBrd != null && !open,
                a.technical() && shown != null && "FAILED".equals(shown.status()) && shown.attempts() < AUTHOR_RETRIES,
                inReview && a.technical(),
                inReview && a.technical() && unaccepted.isEmpty(),
                inReview && (a.author() || a.technical()), unaccepted,
                approved == null ? null : approved.decidedByName(), approved == null ? null : approved.decidedAt(),
                approved == null ? null : approved.note(), outdated, brdNo, technicalApprovers, blocked);
        if (shown != null) {
            audit.record(AuditAction.TDD_VIEWED, "TDD_VERSION", shown.id(), AuditResult.SUCCESS);
        }
        List<HistoryV> history = new ArrayList<>();
        for (VersionRow v : all) {
            if (!insider && !"APPROVED".equals(v.status()) && !"SUPERSEDED".equals(v.status())) {
                continue;
            }
            DecisionRow ok = first(v.id().equals(shown == null ? null : shown.id()) ? decisions : designs.decisions(v.id()),
                    "APPROVED");
            if (!insider && ok == null) {
                continue;
            }
            history.add(new HistoryV(v.versionNo(), v.status(), label(v.status(), ok != null), v.createdAt(),
                    insider ? v.changeRequest() : null, ok == null ? null : ok.decidedByName(),
                    ok == null ? null : ok.decidedAt(), ok != null));
        }
        return new DesignState(requirementId, review, version, history);
    }

    private static String label(String status, boolean approved) {
        return switch (status) {
            case "GENERATING" -> "Being written";
            case "IN_REVIEW" -> "In review";
            case "APPROVED" -> "Approved";
            case "FAILED" -> "Failed";
            case "SUPERSEDED" -> approved ? "Approved, replaced by a later version" : "Replaced by a later version";
            default -> status;
        };
    }

    private static DecisionRow first(List<DecisionRow> decisions, String kind) {
        return decisions.stream().filter(d -> kind.equals(d.kind())).findFirst().orElse(null);
    }

    private VersionV versionView(VersionRow v, List<DecisionRow> decisions) {
        Set<String> accepted = new java.util.HashSet<>();
        decisions.stream().filter(d -> "FLAG_ACCEPTED".equals(d.kind())).forEach(d -> accepted.add(d.itemKey()));
        List<ItemV> items = new ArrayList<>();
        for (ItemRow i : designs.items(v.id())) {
            items.add(new ItemV(i.itemKey(), i.ordinal(), i.section(), i.kind(), i.title(), i.detail(), i.component(),
                    strings(i.brdRefsJson()), i.supportState(), objects(i.evidenceJson()), accepted.contains(i.itemKey())));
        }
        VersionView brd = reviews.view(v.brdVersionId(), false);
        List<CoverageV> coverage = new ArrayList<>();
        if (!"GENERATING".equals(v.status()) && !"FAILED".equals(v.status())) {
            for (ItemView b : brd.items()) {
                if (!COVERED_SECTIONS.contains(b.section())) {
                    continue;
                }
                List<String> by = items.stream().filter(i -> i.brdRefs().contains(b.itemKey())).map(ItemV::title).toList();
                coverage.add(new CoverageV(b.itemKey(), b.section(), b.statement(), !by.isEmpty(), by));
            }
        }
        return new VersionV(v.id(), v.versionNo(), v.status(), brd.versionNo(), v.summary(),
                strings(v.openQuestionsJson()), v.contentHash(), v.modelId(), v.changeRequest(), v.failureCode(),
                v.attempts(), v.createdAt(), items, coverage);
    }

    private List<String> strings(String jsonArray) {
        List<String> out = new ArrayList<>();
        if (jsonArray != null) {
            json.readTree(jsonArray).forEach(n -> out.add(n.asString()));
        }
        return out;
    }

    private List<Map<String, Object>> objects(String jsonArray) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (jsonArray != null) {
            for (JsonNode n : json.readTree(jsonArray)) {
                Map<String, Object> m = new LinkedHashMap<>();
                n.properties().forEach(e -> m.put(e.getKey(), e.getValue().isNumber() ? e.getValue().asInt()
                        : e.getValue().asString()));
                out.add(m);
            }
        }
        return out;
    }

    // --- actions ------------------------------------------------------------------------------------------------

    /** Starts the design of the approved BRD. */
    @Transactional
    public DesignState start(UUID requirementId) {
        Access a = access(requirementId);
        requireTechnical(a, "A Technical Approver of the project starts the technical design.");
        var brd = reviews.latestWithStatus(requirementId, Set.of("RELEASED")).orElseThrow(() ->
                ApiException.conflict("BRD_NOT_APPROVED", "The technical design starts from an approved BRD."));
        if (designs.open(requirementId, true).isPresent()) {
            throw ApiException.conflict("DESIGN_IN_PROGRESS", "A technical design is already in progress.");
        }
        UUID id = designs.insert(TenantContext.requireTenant(), a.projectId(), requirementId, brd.id(), null, a.me());
        request(requirementId, id, 1);
        return state(requirementId);
    }

    /** The author or an approver retries a failed design (at most three attempts in all). */
    @Transactional
    public DesignState retry(UUID requirementId) {
        Access a = access(requirementId);
        requireTechnical(a, "A Technical Approver of the project retries the design.");
        VersionRow v = designs.open(requirementId, true).filter(x -> "FAILED".equals(x.status()))
                .orElseThrow(() -> ApiException.conflict("DESIGN_NOT_FAILED", "There is no failed design to retry."));
        if (v.attempts() >= AUTHOR_RETRIES) {
            throw ApiException.conflict("DESIGN_RETRIES_USED", "The design was tried " + AUTHOR_RETRIES
                    + " times. Contact Garuda support.");
        }
        designs.retry(v.id());
        request(requirementId, v.id(), v.attempts() + 1);
        return state(requirementId);
    }

    private void request(UUID requirementId, UUID versionId, int attempt) {
        outbox.publish("REQUIREMENT", requirementId, EventTypes.TDD_REQUESTED,
                Map.of("requirementId", requirementId.toString(), "tddVersionId", versionId.toString(),
                        "attempt", attempt));
        audit.record(AuditAction.TDD_REQUESTED, "TDD_VERSION", versionId, AuditResult.SUCCESS);
    }

    @Transactional
    public DesignState acceptFlag(UUID requirementId, String itemKey) {
        Access a = access(requirementId);
        VersionRow v = inReview(requirementId);
        requireTechnical(a, "A Technical Approver decides flagged design items.");
        boolean flagged = designs.items(v.id()).stream()
                .anyMatch(i -> i.itemKey().equals(itemKey) && "FLAGGED".equals(i.supportState()));
        if (!flagged) {
            throw ApiException.notFound();
        }
        if (designs.decisions(v.id()).stream().noneMatch(d -> "FLAG_ACCEPTED".equals(d.kind()) && itemKey.equals(d.itemKey()))) {
            designs.insertDecision(TenantContext.requireTenant(), a.projectId(), requirementId, v.id(), "FLAG_ACCEPTED",
                    itemKey, null, a.me());
            audit.record(AuditAction.TDD_DECIDED, "TDD_VERSION", v.id(), AuditResult.SUCCESS);
        }
        return state(requirementId);
    }

    @Transactional
    public DesignState approve(UUID requirementId, String expectedContentHash, boolean confirmedAlone) {
        Access a = access(requirementId);
        VersionRow v = inReview(requirementId);
        requireTechnical(a, "A Technical Approver of the project approves the design.");
        if (expectedContentHash != null && !expectedContentHash.equals(v.contentHash())) {
            throw ApiException.conflict("VERSION_CHANGED", "The design changed since you opened it. Reload it.");
        }
        if (!state(requirementId).review().unacceptedFlags().isEmpty()) {
            throw ApiException.conflict("FLAGS_NOT_ACCEPTED",
                    "Accept or request changes to every flagged design item before you approve.");
        }
        if (!confirmedAlone) {
            throw ApiException.conflict("CONFIRMATION_REQUIRED",
                    "Confirm that you approve this design on your own, without a second reviewer.");
        }
        for (VersionRow old : designs.versions(requirementId)) {
            if ("APPROVED".equals(old.status())) {
                designs.setStatus(old.id(), "SUPERSEDED");
            }
        }
        designs.insertDecision(TenantContext.requireTenant(), a.projectId(), requirementId, v.id(), "APPROVED", null,
                SINGLE_APPROVAL_NOTE, a.me());
        designs.setStatus(v.id(), "APPROVED");
        reviews.notify(TenantContext.requireTenant(), designs.requirementOwner(requirementId), "TDD_APPROVED",
                requirementId);
        audit.record(AuditAction.TDD_APPROVED, "TDD_VERSION", v.id(), AuditResult.SUCCESS);
        return state(requirementId);
    }

    /** The author or an approver requests changes: the agent writes a new version with the request. */
    @Transactional
    public DesignState requestChanges(UUID requirementId, String text) {
        if (text == null || text.strip().length() < 10 || text.length() > 5000) {
            throw ApiException.validation(List.of(new FieldError("text", "LENGTH")));
        }
        Access a = access(requirementId);
        if (!a.author() && !a.technical()) {
            throw forbidden("The requirement's author or a Technical Approver requests changes.");
        }
        VersionRow v = inReview(requirementId);
        designs.insertDecision(TenantContext.requireTenant(), a.projectId(), requirementId, v.id(), "CHANGES_REQUESTED",
                null, text.strip(), a.me());
        designs.setStatus(v.id(), "SUPERSEDED");
        var brd = reviews.latestWithStatus(requirementId, Set.of("RELEASED")).orElseThrow(() ->
                ApiException.conflict("BRD_NOT_APPROVED", "The technical design starts from an approved BRD."));
        UUID next = designs.insert(TenantContext.requireTenant(), a.projectId(), requirementId, brd.id(), text.strip(),
                a.me());
        audit.record(AuditAction.TDD_DECIDED, "TDD_VERSION", v.id(), AuditResult.SUCCESS);
        request(requirementId, next, 1);
        return state(requirementId);
    }

    private VersionRow inReview(UUID requirementId) {
        return designs.open(requirementId, true).filter(v -> "IN_REVIEW".equals(v.status()))
                .orElseThrow(() -> ApiException.conflict("DESIGN_NOT_IN_REVIEW",
                        "This design is not waiting for a review decision."));
    }

    private static void requireTechnical(Access a, String message) {
        if (!a.technical()) {
            throw forbidden(message);
        }
    }

    private static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "DESIGN_DECISION_NOT_ALLOWED", message);
    }

    // --- export -------------------------------------------------------------------------------------------------

    private static final Map<String, String> SECTIONS = sectionTitles();

    private static Map<String, String> sectionTitles() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ARCHITECTURE", "Architecture");
        m.put("COMPONENTS", "Components");
        m.put("API_CHANGES", "API changes");
        m.put("DATA_MODEL", "Data model");
        m.put("INTEGRATIONS", "Integrations");
        m.put("SECURITY", "Security");
        m.put("NON_FUNCTIONAL", "Non-functional design");
        m.put("MIGRATION_AND_ROLLOUT", "Migration and rollout");
        m.put("TEST_PLAN", "Test plan");
        m.put("RISKS", "Risks");
        return m;
    }

    private static final Map<String, String> KINDS = Map.of("DECISION", "Decision", "CHANGE", "Change", "NEW", "New",
            "TASK", "Task", "RISK", "Risk", "ASSUMPTION", "Assumption");

    private static final Map<String, String> SUPPORT = Map.of("VERIFIED", "Based on verified code",
            "FLAGGED", "Code reference not verified; accepted as is by the author", "NOT_TRACED", "Not traced to code");

    /** The approved design (or a given approved version) as a document for PDF or Word. */
    @Transactional
    public BrdDocument export(UUID requirementId, Integer versionNo, ZoneId zone) {
        Access a = access(requirementId);
        VersionRow v = designs.versions(requirementId).stream()
                .filter(x -> versionNo == null ? "APPROVED".equals(x.status()) : x.versionNo() == versionNo)
                .filter(x -> first(designs.decisions(x.id()), "APPROVED") != null)
                .findFirst().orElseThrow(() -> ApiException.conflict("DESIGN_NOT_APPROVED",
                        "Only an approved technical design can be downloaded."));
        List<DecisionRow> decisions = designs.decisions(v.id());
        VersionV view = versionView(v, decisions);
        DateTimeFormatter when = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH).withZone(zone);
        BrdDocument.Header h = reviews.exportHeader(requirementId);
        List<BrdDocument.Section> sections = new ArrayList<>();
        for (Map.Entry<String, String> s : SECTIONS.entrySet()) {
            List<BrdDocument.Entry> entries = view.items().stream().filter(i -> s.getKey().equals(i.section()))
                    .map(i -> {
                        List<String> details = new ArrayList<>();
                        details.add(SUPPORT.getOrDefault(i.supportState(), i.supportState()));
                        if (i.component() != null) {
                            details.add("Component: " + i.component());
                        }
                        if (!i.brdRefs().isEmpty()) {
                            details.add("Addresses " + i.brdRefs().size() + " BRD statement(s)");
                        }
                        List<String> evidence = i.evidence().stream().map(e -> e.get("path") + ", lines "
                                + e.get("startLine") + "–" + e.get("endLine")).toList();
                        return new BrdDocument.Entry(KINDS.getOrDefault(i.kind(), i.kind()),
                                i.title() + ". " + i.detail(), String.join(" · ", details), evidence);
                    }).toList();
            if (!entries.isEmpty()) {
                sections.add(new BrdDocument.Section(s.getValue(), entries));
            }
        }
        if (!view.openQuestions().isEmpty()) {
            sections.add(new BrdDocument.Section("Open questions", view.openQuestions().stream()
                    .map(q -> new BrdDocument.Entry("Question", q, "", List.of())).toList()));
        }
        List<BrdDocument.Entry> coverage = view.coverage().stream().map(c -> new BrdDocument.Entry(
                c.covered() ? "Covered" : "Not covered", c.statement(),
                c.covered() ? "Addressed by: " + String.join("; ", c.coveredBy()) : "No design item addresses this BRD statement",
                List.of())).toList();
        if (!coverage.isEmpty()) {
            sections.add(new BrdDocument.Section("Coverage of the approved BRD", coverage));
        }
        List<BrdDocument.Field> approval = new ArrayList<>();
        for (DecisionRow d : decisions) {
            String label = switch (d.kind()) {
                case "APPROVED" -> "Approved by the Technical Approver";
                case "FLAG_ACCEPTED" -> "Flagged item accepted as is";
                default -> null;
            };
            if (label != null) {
                approval.add(new BrdDocument.Field(label, d.decidedByName() + ", " + when.format(d.decidedAt())
                        + ("APPROVED".equals(d.kind()) && d.note() != null ? ". " + d.note() : "")));
            }
        }
        approval.add(new BrdDocument.Field("Content fingerprint (SHA-256)", v.contentHash()));
        String exported = when.format(OffsetDateTime.now());
        String by = reviews.displayName(a.me());
        List<BrdDocument.Field> cover = List.of(new BrdDocument.Field("Organisation", h.organisation()),
                new BrdDocument.Field("Project", h.project()),
                new BrdDocument.Field("Requirement", h.requirementKey() + " — " + h.title()),
                new BrdDocument.Field("Design version", v.versionNo() + ", produced " + when.format(v.createdAt())),
                new BrdDocument.Field("Based on", "Approved BRD version " + view.brdVersionNo()),
                new BrdDocument.Field("Exported", exported + " by " + by));
        audit.record(AuditAction.TDD_EXPORTED, "TDD_VERSION", v.id(), AuditResult.SUCCESS);
        return new BrdDocument(BrdDocument.DocKind.TDD, cover, h.organisation(), h.project(), h.requirementKey(),
                h.title(), List.of(), v.versionNo(),
                "APPROVED".equals(v.status()) ? "Approved" : "Approved, replaced by a later version", v.contentHash(),
                when.format(v.createdAt()), null, List.of(), v.summary(), sections, List.of(), approval, exported, by);
    }
}
