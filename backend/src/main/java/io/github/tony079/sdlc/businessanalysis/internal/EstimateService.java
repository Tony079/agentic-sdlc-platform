package io.github.tony079.sdlc.businessanalysis.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.EstimateRepository.AdjustmentRow;
import io.github.tony079.sdlc.businessanalysis.internal.EstimateRepository.DecisionRow;
import io.github.tony079.sdlc.businessanalysis.internal.EstimateRepository.ItemRow;
import io.github.tony079.sdlc.businessanalysis.internal.EstimateRepository.VersionRow;
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
 * GD-33: the Estimation stage. Estimators start, review and adjust an estimate from the approved design; a BRD
 * Approver approves it (alone, after an explicit confirmation recorded on the approval). Only Estimators and BRD
 * Approvers see estimate content; other project members see whether an estimate exists and its status. The database
 * enforces the same (row-level security and guards).
 */
@Service
@Profile("!migrate & !provision")
class EstimateService {

    static final int RETRIES = 3;
    static final List<String> ROLES = List.of("FRONTEND", "BACKEND", "DATA", "DEVOPS", "QA", "DESIGN", "OTHER");
    static final String SINGLE_APPROVAL_NOTE = "Approved by a single BRD Approver after explicit confirmation; "
            + "no second reviewer.";

    record AdjustmentV(double low, double likely, double high, String reason, String by, OffsetDateTime at) {
    }

    record ItemV(String itemKey, int ordinal, String title, String detail, String role, String phase,
                 List<String> designRefs, double proposedLow, double proposedLikely, double proposedHigh, double low,
                 double likely, double high, double expected, List<String> assumptions, String risk,
                 List<String> dependsOn, List<String> flags, boolean accepted, List<AdjustmentV> adjustments) {
    }

    record CoverageV(String designKey, String section, String title, boolean covered, boolean accepted,
                     List<String> coveredBy) {
    }

    record VersionV(UUID id, int versionNo, String status, int designVersionNo, String contentHash, String reviewHash,
                    String modelId, String changeRequest, String failureCode, int attempts, OffsetDateTime createdAt,
                    Map<String, Object> settings, List<String> assumptions, List<String> excluded, List<ItemV> items,
                    List<CoverageV> coverage, EstimateCalculator.Summary summary) {
    }

    record ReviewV(String stage, boolean isEstimator, boolean isApprover, boolean canStart, boolean canRetry,
                   boolean canAdjust, boolean canApprove, boolean canRequestChanges, List<String> unacceptedFlags,
                   List<String> uncoveredDesignItems, String approvedBy, OffsetDateTime approvedAt, String approvalNote,
                   boolean designOutdated, Integer currentDesignVersionNo, int estimators, String blockedReason) {
    }

    record HistoryV(int versionNo, String status, OffsetDateTime createdAt, String changeRequest, String approvedBy,
                    OffsetDateTime approvedAt) {
    }

    /** What every project member may see: whether there is an estimate, and its status. */
    record EstimateState(UUID requirementId, boolean canSee, ReviewV review, VersionV version, List<HistoryV> history,
                         Map<String, Object> defaults) {
    }

    private record Access(UUID me, UUID projectId, boolean estimator, boolean approver) {
        boolean insider() {
            return estimator || approver;
        }
    }

    private final EstimateRepository estimates;
    private final DesignRepository designs;
    private final ReviewRepository reviews;
    private final RequirementDirectory requirements;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final JsonMapper json;

    EstimateService(EstimateRepository estimates, DesignRepository designs, ReviewRepository reviews,
                    RequirementDirectory requirements, OutboxPublisher outbox, AuditWriter audit, JsonMapper json) {
        this.estimates = estimates;
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
        return new Access(me, visible.projectId(), visible.estimator(), reviews.isBrdApprover(visible.projectId()));
    }

    // --- read ---------------------------------------------------------------------------------------------------

    @Transactional
    public EstimateState state(UUID requirementId) {
        Access a = access(requirementId);
        UUID approvedDesign = estimates.approvedDesign(requirementId).orElse(null);
        List<VersionRow> all = estimates.versions(requirementId);
        VersionRow shown = all.stream().findFirst().orElse(null);
        // A member sees only their own membership (row-level security): an Estimator counts at least themselves.
        int estimators = estimates.membersWithRole(a.projectId(), "ESTIMATOR").size();
        String stage = approvedDesign == null ? "DESIGN_NOT_APPROVED"
                : shown == null ? "NOT_STARTED"
                : switch (shown.status()) {
                    case "GENERATING" -> "GENERATING";
                    case "FAILED" -> "FAILED";
                    case "IN_REVIEW" -> "IN_REVIEW";
                    case "APPROVED" -> "APPROVED";
                    default -> all.stream().anyMatch(v -> "APPROVED".equals(v.status())) ? "APPROVED" : "NOT_STARTED";
                };
        if (!a.insider()) {
            // GD-33: other members see that an estimate exists and its status, not its content.
            ReviewV review = new ReviewV(stage, false, false, false, false, false, false, false, List.of(), List.of(),
                    null, null, null, false, null, estimators, null);
            return new EstimateState(requirementId, false, review, null, List.of(), null);
        }
        List<DecisionRow> decisions = shown == null ? List.of() : estimates.decisions(shown.id());
        DecisionRow approved = first(decisions, "APPROVED");
        VersionV version = shown == null ? null : versionView(shown, decisions);
        boolean inReview = shown != null && "IN_REVIEW".equals(shown.status());
        boolean open = all.stream().anyMatch(v -> Set.of("GENERATING", "IN_REVIEW", "FAILED").contains(v.status()));
        List<String> unaccepted = version == null ? List.of() : version.items().stream()
                .filter(i -> !i.flags().isEmpty() && !i.accepted() && !adjusted(i)).map(ItemV::itemKey).toList();
        List<String> gaps = version == null ? List.of() : version.coverage().stream()
                .filter(c -> !c.covered() && !c.accepted()).map(CoverageV::designKey).toList();
        Integer designNo = approvedDesign == null ? null
                : designs.find(approvedDesign, false).map(DesignRepository.VersionRow::versionNo).orElse(null);
        boolean outdated = shown != null && approvedDesign != null && !approvedDesign.equals(shown.tddVersionId());
        String blocked = null;
        if (approvedDesign != null && shown == null && !a.estimator()) {
            blocked = "Waiting for an Estimator of the project to start the estimate. If the project has none, your "
                    + "Organisation Admin can assign one.";
        } else if (inReview && (!unaccepted.isEmpty() || !gaps.isEmpty())) {
            blocked = (unaccepted.isEmpty() ? "" : unaccepted.size() + " flagged work item(s)")
                    + (!unaccepted.isEmpty() && !gaps.isEmpty() ? " and " : "")
                    + (gaps.isEmpty() ? "" : gaps.size() + " uncovered design item(s)")
                    + " need an Estimator's decision before the estimate can be approved.";
        }
        ReviewV review = new ReviewV(stage, a.estimator(), a.approver(),
                a.estimator() && approvedDesign != null && !open,
                a.estimator() && shown != null && "FAILED".equals(shown.status()) && shown.attempts() < RETRIES,
                inReview && a.estimator(),
                inReview && a.approver() && unaccepted.isEmpty() && gaps.isEmpty(),
                inReview, unaccepted, gaps,
                approved == null ? null : approved.decidedByName(), approved == null ? null : approved.decidedAt(),
                approved == null ? null : approved.note(), outdated, designNo, estimators, blocked);
        if (shown != null) {
            audit.record(AuditAction.ESTIMATE_VIEWED, "ESTIMATE_VERSION", shown.id(), AuditResult.SUCCESS);
        }
        List<HistoryV> history = new ArrayList<>();
        for (VersionRow v : all) {
            DecisionRow ok = first(v.id().equals(shown == null ? null : shown.id()) ? decisions : estimates.decisions(v.id()),
                    "APPROVED");
            history.add(new HistoryV(v.versionNo(), v.status(), v.createdAt(), v.changeRequest(),
                    ok == null ? null : ok.decidedByName(), ok == null ? null : ok.decidedAt()));
        }
        return new EstimateState(requirementId, true, review, version, history,
                settingsMap(estimates.defaults(a.projectId()).orElse(null)));
    }

    private static boolean adjusted(ItemV item) {
        return !item.adjustments().isEmpty();
    }

    private static DecisionRow first(List<DecisionRow> decisions, String kind) {
        return decisions.stream().filter(d -> kind.equals(d.kind())).findFirst().orElse(null);
    }

    private VersionV versionView(VersionRow v, List<DecisionRow> decisions) {
        Set<String> acceptedFlags = new java.util.HashSet<>();
        Set<String> acceptedGaps = new java.util.HashSet<>();
        for (DecisionRow d : decisions) {
            if ("FLAG_ACCEPTED".equals(d.kind())) {
                acceptedFlags.add(d.itemKey());
            } else if ("GAP_ACCEPTED".equals(d.kind())) {
                acceptedGaps.add(d.itemKey());
            }
        }
        Map<String, List<AdjustmentV>> adjustments = new HashMap<>();
        for (AdjustmentRow r : estimates.adjustments(v.id())) {
            adjustments.computeIfAbsent(r.itemKey(), k -> new ArrayList<>()).add(new AdjustmentV(r.low().doubleValue(),
                    r.likely().doubleValue(), r.high().doubleValue(), r.reason(), r.adjustedByName(), r.adjustedAt()));
        }
        List<ItemV> items = new ArrayList<>();
        List<EstimateCalculator.Figures> figures = new ArrayList<>();
        for (ItemRow i : estimates.items(v.id())) {
            List<AdjustmentV> adj = adjustments.getOrDefault(i.itemKey(), List.of());
            AdjustmentV last = adj.isEmpty() ? null : adj.getLast();
            double low = last == null ? i.low().doubleValue() : last.low();
            double likely = last == null ? i.likely().doubleValue() : last.likely();
            double high = last == null ? i.high().doubleValue() : last.high();
            List<String> deps = strings(i.dependsOnJson());
            EstimateCalculator.Figures f = new EstimateCalculator.Figures(i.itemKey(), i.role(), i.phase(), low, likely,
                    high, deps, i.title());
            figures.add(f);
            items.add(new ItemV(i.itemKey(), i.ordinal(), i.title(), i.detail(), i.role(), i.phase(),
                    strings(i.designRefsJson()), i.low().doubleValue(), i.likely().doubleValue(), i.high().doubleValue(),
                    low, likely, high, EstimateCalculator.round(f.expected()), strings(i.assumptionsJson()), i.risk(),
                    deps, strings(i.flagsJson()), acceptedFlags.contains(i.itemKey()), adj));
        }
        List<CoverageV> coverage = new ArrayList<>();
        int designNo = 0;
        var design = designs.find(v.tddVersionId(), false).orElse(null);
        if (design != null) {
            designNo = design.versionNo();
            if (!"GENERATING".equals(v.status()) && !"FAILED".equals(v.status())) {
                for (DesignRepository.ItemRow d : designs.items(design.id())) {
                    List<String> by = items.stream().filter(i -> i.designRefs().contains(d.itemKey())).map(ItemV::title)
                            .toList();
                    coverage.add(new CoverageV(d.itemKey(), d.section(), d.title(), !by.isEmpty(),
                            acceptedGaps.contains(d.itemKey()), by));
                }
            }
        }
        EstimateCalculator.Summary summary = items.isEmpty() ? null
                : EstimateCalculator.summarise(figures, settings(v.settingsJson()));
        return new VersionV(v.id(), v.versionNo(), v.status(), designNo, v.contentHash(), reviewHash(v, items),
                v.modelId(), v.changeRequest(), v.failureCode(), v.attempts(), v.createdAt(),
                settingsMap(v.settingsJson()), strings(v.assumptionsJson()), strings(v.excludedJson()), items, coverage,
                summary);
    }

    /** What an approval binds: the produced content plus every adjustment in effect. */
    private String reviewHash(VersionRow v, List<ItemV> items) {
        if (v.contentHash() == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(v.contentHash());
        for (ItemV i : items) {
            text.append('|').append(i.itemKey()).append(':').append(i.low()).append('/').append(i.likely()).append('/')
                    .append(i.high());
        }
        return InputSets.sha256(text.toString());
    }

    private List<String> strings(String jsonArray) {
        List<String> out = new ArrayList<>();
        if (jsonArray != null) {
            json.readTree(jsonArray).forEach(n -> out.add(n.asString()));
        }
        return out;
    }

    // --- settings -----------------------------------------------------------------------------------------------

    static final String DEFAULT_SETTINGS = "{\"team\":{\"BACKEND\":1,\"FRONTEND\":1,\"QA\":1},\"daysPerWeek\":5,"
            + "\"contingencyPercent\":15,\"currency\":null,\"dayRates\":null}";

    @SuppressWarnings("unchecked")
    private Map<String, Object> settingsMap(String settingsJson) {
        return json.readValue(settingsJson == null ? DEFAULT_SETTINGS : settingsJson, LinkedHashMap.class);
    }

    EstimateCalculator.Settings settings(String settingsJson) {
        JsonNode node = json.readTree(settingsJson == null ? DEFAULT_SETTINGS : settingsJson);
        Map<String, Integer> team = new LinkedHashMap<>();
        node.path("team").properties().forEach(e -> team.put(e.getKey(), e.getValue().asInt(0)));
        Map<String, BigDecimal> rates = null;
        if (node.path("dayRates").isObject()) {
            rates = new LinkedHashMap<>();
            for (var e : node.path("dayRates").properties()) {
                rates.put(e.getKey(), e.getValue().decimalValue());
            }
        }
        String currency = node.path("currency").isString() ? node.path("currency").asString() : null;
        return new EstimateCalculator.Settings(team, node.path("daysPerWeek").asInt(5),
                node.path("contingencyPercent").asInt(0), currency, rates);
    }

    /** Validates settings from the client and returns their canonical JSON. */
    String validSettings(Map<String, Object> input) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> team = new LinkedHashMap<>();
        if (input == null) {
            return DEFAULT_SETTINGS;
        }
        if (input.get("team") instanceof Map<?, ?> t) {
            for (Map.Entry<?, ?> e : t.entrySet()) {
                String role = String.valueOf(e.getKey());
                Integer n = e.getValue() instanceof Number num ? num.intValue() : null;
                if (!ROLES.contains(role) || n == null || n < 0 || n > 50) {
                    errors.add(new FieldError("team." + role, "INVALID"));
                } else if (n > 0) {
                    team.put(role, n);
                }
            }
        }
        out.put("team", team);
        int days = input.get("daysPerWeek") instanceof Number n ? n.intValue() : 5;
        if (days < 1 || days > 7) {
            errors.add(new FieldError("daysPerWeek", "RANGE"));
        }
        out.put("daysPerWeek", days);
        int contingency = input.get("contingencyPercent") instanceof Number n ? n.intValue() : 0;
        if (contingency < 0 || contingency > 100) {
            errors.add(new FieldError("contingencyPercent", "RANGE"));
        }
        out.put("contingencyPercent", contingency);
        Object currency = input.get("currency");
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        if (input.get("dayRates") instanceof Map<?, ?> r) {
            for (Map.Entry<?, ?> e : r.entrySet()) {
                String role = String.valueOf(e.getKey());
                BigDecimal rate = e.getValue() instanceof Number num ? new BigDecimal(num.toString()) : null;
                if (!ROLES.contains(role) || rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
                    errors.add(new FieldError("dayRates." + role, "INVALID"));
                } else if (rate.signum() > 0) {
                    rates.put(role, rate.setScale(2, RoundingMode.HALF_UP));
                }
            }
        }
        if (!rates.isEmpty() && (!(currency instanceof String c) || !c.matches("^[A-Z]{3}$"))) {
            errors.add(new FieldError("currency", "REQUIRED_WITH_RATES"));
        }
        out.put("currency", rates.isEmpty() ? null : currency);
        out.put("dayRates", rates.isEmpty() ? null : rates);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return json.writeValueAsString(out);
    }

    // --- actions ------------------------------------------------------------------------------------------------

    @Transactional
    public EstimateState start(UUID requirementId, Map<String, Object> settingsInput) {
        Access a = access(requirementId);
        requireEstimator(a, "An Estimator of the project starts the estimate.");
        UUID design = estimates.approvedDesign(requirementId).orElseThrow(() ->
                ApiException.conflict("DESIGN_NOT_APPROVED", "The estimate starts from an approved technical design."));
        if (estimates.open(requirementId, true).isPresent()) {
            throw ApiException.conflict("ESTIMATE_IN_PROGRESS", "An estimate is already in progress.");
        }
        String settings = validSettings(settingsInput);
        estimates.saveDefaults(TenantContext.requireTenant(), a.projectId(), settings, a.me());
        UUID id = estimates.insert(TenantContext.requireTenant(), a.projectId(), requirementId, design, settings, null,
                a.me());
        request(requirementId, id, 1);
        return state(requirementId);
    }

    @Transactional
    public EstimateState retry(UUID requirementId) {
        Access a = access(requirementId);
        requireEstimator(a, "An Estimator of the project retries the estimate.");
        VersionRow v = estimates.open(requirementId, true).filter(x -> "FAILED".equals(x.status()))
                .orElseThrow(() -> ApiException.conflict("ESTIMATE_NOT_FAILED", "There is no failed estimate to retry."));
        if (v.attempts() >= RETRIES) {
            throw ApiException.conflict("ESTIMATE_RETRIES_USED", "The estimate was tried " + RETRIES
                    + " times. Contact Garuda support.");
        }
        estimates.retry(v.id());
        request(requirementId, v.id(), v.attempts() + 1);
        return state(requirementId);
    }

    private void request(UUID requirementId, UUID versionId, int attempt) {
        outbox.publish("REQUIREMENT", requirementId, EventTypes.ESTIMATE_REQUESTED,
                Map.of("requirementId", requirementId.toString(), "estimateVersionId", versionId.toString(),
                        "attempt", attempt));
        audit.record(AuditAction.ESTIMATE_REQUESTED, "ESTIMATE_VERSION", versionId, AuditResult.SUCCESS);
    }

    /** An Estimator keeps a flagged work item as it is. */
    @Transactional
    public EstimateState acceptFlag(UUID requirementId, String itemKey) {
        Access a = access(requirementId);
        requireEstimator(a, "An Estimator decides flagged work items.");
        VersionRow v = inReview(requirementId);
        boolean flagged = estimates.items(v.id()).stream()
                .anyMatch(i -> i.itemKey().equals(itemKey) && !"[]".equals(i.flagsJson().replace(" ", "")));
        if (!flagged) {
            throw ApiException.notFound();
        }
        decideOnce(a, requirementId, v, "FLAG_ACCEPTED", itemKey);
        return state(requirementId);
    }

    /** An Estimator knowingly leaves a design item without estimated work (for example, covered elsewhere). */
    @Transactional
    public EstimateState acceptGap(UUID requirementId, String designKey) {
        Access a = access(requirementId);
        requireEstimator(a, "An Estimator decides design items left without work.");
        VersionRow v = inReview(requirementId);
        if (designs.items(v.tddVersionId()).stream().noneMatch(d -> d.itemKey().equals(designKey))) {
            throw ApiException.notFound();
        }
        decideOnce(a, requirementId, v, "GAP_ACCEPTED", designKey);
        return state(requirementId);
    }

    private void decideOnce(Access a, UUID requirementId, VersionRow v, String kind, String key) {
        if (estimates.decisions(v.id()).stream().noneMatch(d -> kind.equals(d.kind()) && key.equals(d.itemKey()))) {
            estimates.insertDecision(TenantContext.requireTenant(), a.projectId(), requirementId, v.id(), kind, key,
                    null, a.me());
            audit.record(AuditAction.ESTIMATE_DECIDED, "ESTIMATE_VERSION", v.id(), AuditResult.SUCCESS);
        }
    }

    /** An Estimator changes an item's figures, with a reason; the proposal and every adjustment are kept. */
    @Transactional
    public EstimateState adjust(UUID requirementId, String itemKey, Double low, Double likely, Double high,
                                String reason) {
        Access a = access(requirementId);
        requireEstimator(a, "An Estimator of the project adjusts the estimate.");
        VersionRow v = inReview(requirementId);
        List<FieldError> errors = new ArrayList<>();
        if (low == null || likely == null || high == null || low <= 0 || likely < low || high < likely || high > 999) {
            errors.add(new FieldError("days", "ORDER"));
        }
        if (reason == null || reason.strip().length() < 10 || reason.length() > 1000) {
            errors.add(new FieldError("reason", "LENGTH"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        if (estimates.items(v.id()).stream().noneMatch(i -> i.itemKey().equals(itemKey))) {
            throw ApiException.notFound();
        }
        estimates.insertAdjustment(TenantContext.requireTenant(), a.projectId(), v.id(), itemKey, days(low),
                days(likely), days(high), reason.strip(), a.me());
        audit.record(AuditAction.ESTIMATE_ADJUSTED, "ESTIMATE_VERSION", v.id(), AuditResult.SUCCESS);
        return state(requirementId);
    }

    private static BigDecimal days(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional
    public EstimateState approve(UUID requirementId, String expectedReviewHash, boolean confirmedAlone) {
        Access a = access(requirementId);
        if (!a.approver()) {
            throw forbidden("A BRD Approver of the project approves the estimate.");
        }
        VersionRow v = inReview(requirementId);
        EstimateState current = state(requirementId);
        if (expectedReviewHash != null && !expectedReviewHash.equals(current.version().reviewHash())) {
            throw ApiException.conflict("VERSION_CHANGED", "The estimate changed since you opened it. Reload it.");
        }
        if (!current.review().unacceptedFlags().isEmpty() || !current.review().uncoveredDesignItems().isEmpty()) {
            throw ApiException.conflict("REVIEW_INCOMPLETE",
                    "An Estimator must decide every flagged work item and uncovered design item first.");
        }
        if (!confirmedAlone) {
            throw ApiException.conflict("CONFIRMATION_REQUIRED",
                    "Confirm that you approve this estimate on your own, without a second reviewer.");
        }
        for (VersionRow old : estimates.versions(requirementId)) {
            if ("APPROVED".equals(old.status())) {
                estimates.setStatus(old.id(), "SUPERSEDED");
            }
        }
        estimates.insertDecision(TenantContext.requireTenant(), a.projectId(), requirementId, v.id(), "APPROVED", null,
                SINGLE_APPROVAL_NOTE, a.me());
        estimates.setStatus(v.id(), "APPROVED");
        for (UUID user : new LinkedHashSet<>(estimates.membersWithRole(a.projectId(), "ESTIMATOR"))) {
            reviews.notify(TenantContext.requireTenant(), user, "ESTIMATE_APPROVED", requirementId);
        }
        audit.record(AuditAction.ESTIMATE_APPROVED, "ESTIMATE_VERSION", v.id(), AuditResult.SUCCESS);
        return state(requirementId);
    }

    /** An Estimator or a BRD Approver requests a new version; it revises this one. */
    @Transactional
    public EstimateState requestChanges(UUID requirementId, String text) {
        if (text == null || text.strip().length() < 10 || text.length() > 5000) {
            throw ApiException.validation(List.of(new FieldError("text", "LENGTH")));
        }
        Access a = access(requirementId);
        if (!a.insider()) {
            throw forbidden("An Estimator or a BRD Approver requests changes.");
        }
        VersionRow v = inReview(requirementId);
        estimates.insertDecision(TenantContext.requireTenant(), a.projectId(), requirementId, v.id(), "CHANGES_REQUESTED",
                null, text.strip(), a.me());
        estimates.setStatus(v.id(), "SUPERSEDED");
        UUID design = estimates.approvedDesign(requirementId).orElseThrow(() ->
                ApiException.conflict("DESIGN_NOT_APPROVED", "The estimate starts from an approved technical design."));
        UUID next = estimates.insert(TenantContext.requireTenant(), a.projectId(), requirementId, design,
                v.settingsJson(), text.strip(), a.me());
        audit.record(AuditAction.ESTIMATE_DECIDED, "ESTIMATE_VERSION", v.id(), AuditResult.SUCCESS);
        request(requirementId, next, 1);
        return state(requirementId);
    }

    private VersionRow inReview(UUID requirementId) {
        return estimates.open(requirementId, true).filter(v -> "IN_REVIEW".equals(v.status()))
                .orElseThrow(() -> ApiException.conflict("ESTIMATE_NOT_IN_REVIEW",
                        "This estimate is not waiting for a review decision."));
    }

    private static void requireEstimator(Access a, String message) {
        if (!a.estimator()) {
            throw forbidden(message);
        }
    }

    private static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "ESTIMATE_DECISION_NOT_ALLOWED", message);
    }

    // --- export -------------------------------------------------------------------------------------------------

    static final Map<String, String> ROLE_LABELS = Map.of("FRONTEND", "Frontend", "BACKEND", "Backend", "DATA", "Data",
            "DEVOPS", "DevOps", "QA", "QA", "DESIGN", "Design", "OTHER", "Other");
    static final Map<String, String> PHASE_LABELS = Map.of("SETUP", "Setup", "BUILD", "Build", "TEST", "Test",
            "HARDENING", "Hardening", "RELEASE", "Release");

    /** The approved estimate (or a given approved version), for PDF, Word or a spreadsheet. */
    @Transactional
    public VersionV approvedVersion(UUID requirementId, Integer versionNo) {
        Access a = access(requirementId);
        if (!a.insider()) {
            throw ApiException.notFound();
        }
        VersionRow v = estimates.versions(requirementId).stream()
                .filter(x -> versionNo == null ? "APPROVED".equals(x.status()) : x.versionNo() == versionNo)
                .filter(x -> first(estimates.decisions(x.id()), "APPROVED") != null)
                .findFirst().orElseThrow(() -> ApiException.conflict("ESTIMATE_NOT_APPROVED",
                        "Only an approved estimate can be downloaded."));
        audit.record(AuditAction.ESTIMATE_EXPORTED, "ESTIMATE_VERSION", v.id(), AuditResult.SUCCESS);
        return versionView(v, estimates.decisions(v.id()));
    }

    /** One row per work item, with the figures in effect and the expected effort (RFC 4180 CSV). */
    static String csv(VersionV v) {
        StringBuilder out = new StringBuilder("Item,Title,Role,Phase,Low days,Likely days,High days,Expected days,"
                + "Adjusted,Design items,Depends on,Assumptions,Risk\r\n");
        for (ItemV i : v.items()) {
            out.append(i.ordinal()).append(',').append(cell(i.title())).append(',')
                    .append(ROLE_LABELS.getOrDefault(i.role(), i.role())).append(',')
                    .append(PHASE_LABELS.getOrDefault(i.phase(), i.phase())).append(',')
                    .append(i.low()).append(',').append(i.likely()).append(',').append(i.high()).append(',')
                    .append(i.expected()).append(',').append(i.adjustments().isEmpty() ? "No" : "Yes").append(',')
                    .append(i.designRefs().size()).append(',').append(cell(String.join("; ", i.dependsOn()))).append(',')
                    .append(cell(String.join("; ", i.assumptions()))).append(',')
                    .append(cell(i.risk() == null ? "" : i.risk())).append("\r\n");
        }
        return out.toString();
    }

    /** Quoted; a leading formula character is neutralised so a spreadsheet never evaluates text from the model. */
    static String cell(String text) {
        String t = text == null ? "" : text;
        if (!t.isEmpty() && "=+-@\t\r".indexOf(t.charAt(0)) >= 0) {
            t = "'" + t;
        }
        return "\"" + t.replace("\"", "\"\"") + "\"";
    }

    @Transactional(readOnly = true)
    public BrdDocument document(UUID requirementId, VersionV view, ZoneId zone) {
        DateTimeFormatter when = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH).withZone(zone);
        BrdDocument.Header h = reviews.exportHeader(requirementId);
        List<BrdDocument.Section> sections = new ArrayList<>();
        EstimateCalculator.Summary s = view.summary();
        if (s != null) {
            List<BrdDocument.Entry> totals = new ArrayList<>();
            totals.add(new BrdDocument.Entry("Effort", days(s.effortWithContingency()) + " person-days (P50 to P85), "
                    + "including " + s.contingencyPercent() + "% contingency", "Without contingency: "
                    + days(s.effort()), List.of()));
            totals.add(new BrdDocument.Entry("Timeline", weeks(s.weeks()) + " weeks", "For the team in the settings",
                    List.of()));
            if (s.costP50() != null) {
                totals.add(new BrdDocument.Entry("Cost", s.currency() + " " + s.costP50().toPlainString() + " to "
                        + s.currency() + " " + s.costP85().toPlainString(), "From the day rates in the settings",
                        List.of()));
            }
            for (EstimateCalculator.RoleLine r : s.byRole()) {
                totals.add(new BrdDocument.Entry(ROLE_LABELS.getOrDefault(r.role(), r.role()),
                        one(r.effortP50()) + " to " + one(r.effortP85()) + " person-days", r.people() + " people"
                        + (r.assumedPerson() ? " (assumed)" : "") + ", " + one(r.weeksP50()) + " to "
                        + one(r.weeksP85()) + " weeks", List.of()));
            }
            s.notes().forEach(n -> totals.add(new BrdDocument.Entry("Note", n, "", List.of())));
            sections.add(new BrdDocument.Section("Totals", totals));
        }
        for (String phase : List.of("SETUP", "BUILD", "TEST", "HARDENING", "RELEASE")) {
            List<BrdDocument.Entry> entries = view.items().stream().filter(i -> phase.equals(i.phase()))
                    .map(i -> new BrdDocument.Entry(ROLE_LABELS.getOrDefault(i.role(), i.role()),
                            i.title() + ". " + i.detail(), one(i.low()) + " / " + one(i.likely()) + " / " + one(i.high())
                            + " days (expected " + one(i.expected()) + ")"
                            + (i.adjustments().isEmpty() ? "" : " · adjusted: " + i.adjustments().getLast().reason()),
                            i.assumptions())).toList();
            if (!entries.isEmpty()) {
                sections.add(new BrdDocument.Section(PHASE_LABELS.get(phase), entries));
            }
        }
        if (!view.assumptions().isEmpty()) {
            sections.add(new BrdDocument.Section("Assumptions", view.assumptions().stream()
                    .map(x -> new BrdDocument.Entry("Assumption", x, "", List.of())).toList()));
        }
        if (!view.excluded().isEmpty()) {
            sections.add(new BrdDocument.Section("Not included", view.excluded().stream()
                    .map(x -> new BrdDocument.Entry("Excluded", x, "", List.of())).toList()));
        }
        List<BrdDocument.Field> approval = new ArrayList<>();
        for (DecisionRow d : estimates.decisions(view.id())) {
            if ("APPROVED".equals(d.kind())) {
                approval.add(new BrdDocument.Field("Approved by the BRD Approver", d.decidedByName() + ", "
                        + when.format(d.decidedAt()) + ". " + d.note()));
            }
        }
        approval.add(new BrdDocument.Field("Content fingerprint (SHA-256)", view.reviewHash()));
        String exported = when.format(OffsetDateTime.now());
        String by = reviews.displayName(TenantContext.currentActor().orElseThrow());
        List<BrdDocument.Field> cover = List.of(new BrdDocument.Field("Organisation", h.organisation()),
                new BrdDocument.Field("Project", h.project()),
                new BrdDocument.Field("Requirement", h.requirementKey() + " — " + h.title()),
                new BrdDocument.Field("Estimate version", view.versionNo() + ", produced " + when.format(view.createdAt())),
                new BrdDocument.Field("Based on", "Approved technical design version " + view.designVersionNo()),
                new BrdDocument.Field("Exported", exported + " by " + by));
        String summaryText = s == null ? "" : "Expected effort " + days(s.effortWithContingency())
                + " person-days and " + weeks(s.weeks()) + " weeks for the team in the settings"
                + (s.costP50() == null ? "." : ", cost " + s.currency() + " " + s.costP50().toPlainString() + " to "
                + s.currency() + " " + s.costP85().toPlainString() + ".")
                + " Figures are P50 to P85: a 50% and an 85% chance of finishing within them.";
        return new BrdDocument(BrdDocument.DocKind.ESTIMATE, cover, h.organisation(), h.project(), h.requirementKey(),
                h.title(), List.of(), view.versionNo(), "APPROVED".equals(view.status()) ? "Approved"
                : "Approved, replaced by a later version", view.reviewHash(), when.format(view.createdAt()), null,
                List.of(), summaryText, sections, List.of(), approval, exported, by);
    }

    private static String one(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String days(EstimateCalculator.Range r) {
        return one(r.p50()) + " to " + one(r.p85());
    }

    private static String weeks(EstimateCalculator.Range r) {
        return one(r.p50()) + " to " + one(r.p85());
    }
}
