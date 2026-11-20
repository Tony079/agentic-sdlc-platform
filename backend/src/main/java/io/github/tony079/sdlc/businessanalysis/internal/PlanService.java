package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
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
import io.github.tony079.sdlc.businessanalysis.internal.PlanRepository.DecisionRow;
import io.github.tony079.sdlc.businessanalysis.internal.PlanRepository.EditRow;
import io.github.tony079.sdlc.businessanalysis.internal.PlanRepository.EpicRow;
import io.github.tony079.sdlc.businessanalysis.internal.PlanRepository.StoryRow;
import io.github.tony079.sdlc.businessanalysis.internal.PlanRepository.VersionRow;
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
 * GD-34: the Delivery plan stage. Estimators start, edit and review the plan; a BRD Approver approves it (alone, after
 * an explicit confirmation). Drafts are for Estimators and BRD Approvers; the approved plan is for the whole project.
 * Effort comes from the approved estimate, sprints from the team's capacity; the model does neither.
 */
@Service
@Profile("!migrate & !provision")
class PlanService {

    static final int RETRIES = 3;
    static final String SINGLE_APPROVAL_NOTE = "Approved by a single BRD Approver after explicit confirmation; "
            + "no second reviewer.";

    record EditV(Map<String, Object> changes, String reason, String by, OffsetDateTime at) {
    }

    record StoryV(String storyKey, int ordinal, String epicKey, String title, List<String> acceptanceCriteria,
                  List<String> estimateRefs, List<String> brdRefs, String role, String priority, List<String> dependsOn,
                  double effortDays, Integer sprint, boolean pinned, List<String> flags, boolean accepted,
                  List<EditV> edits) {
    }

    record EpicV(String epicKey, String title, String goal, int stories, double effortDays) {
    }

    record GapV(String key, String kind, String title, boolean accepted) {
    }

    record SprintV(int number, java.time.LocalDate start, java.time.LocalDate end, Map<String, Double> load,
                   Map<String, Double> capacity, boolean overCapacity, double effortDays) {
    }

    record VersionV(UUID id, int versionNo, String status, int estimateVersionNo, String contentHash, String reviewHash,
                    String changeRequest, String failureCode, int attempts, OffsetDateTime createdAt,
                    Map<String, Object> settings, List<String> risks, List<EpicV> epics, List<StoryV> stories,
                    List<SprintV> sprints, List<GapV> gaps, double totalEffortDays, Double estimateEffortDays,
                    List<String> notes) {
    }

    record ReviewV(String stage, boolean isEstimator, boolean isApprover, boolean canStart, boolean canRetry,
                   boolean canEdit, boolean canApprove, boolean canRequestChanges, List<String> unacceptedFlags,
                   List<String> openGaps, String approvedBy, OffsetDateTime approvedAt, String approvalNote,
                   boolean estimateOutdated, String blockedReason) {
    }

    record HistoryV(int versionNo, String status, OffsetDateTime createdAt, String changeRequest, String approvedBy,
                    OffsetDateTime approvedAt) {
    }

    record PlanState(UUID requirementId, boolean canSee, boolean insider, ReviewV review, VersionV version,
                     List<HistoryV> history, Map<String, Object> defaults) {
    }

    private record Access(UUID me, UUID projectId, boolean estimator, boolean approver) {
        boolean insider() {
            return estimator || approver;
        }
    }

    private final PlanRepository plans;
    private final EstimateRepository estimates;
    private final ReviewRepository reviews;
    private final RequirementDirectory requirements;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final JsonMapper json;

    PlanService(PlanRepository plans, EstimateRepository estimates, ReviewRepository reviews,
                RequirementDirectory requirements, OutboxPublisher outbox, AuditWriter audit, JsonMapper json) {
        this.plans = plans;
        this.estimates = estimates;
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
    public PlanState state(UUID requirementId) {
        Access a = access(requirementId);
        UUID approvedEstimate = plans.approvedEstimate(requirementId).orElse(null);
        List<VersionRow> all = plans.versions(requirementId);
        VersionRow latest = all.stream().findFirst().orElse(null);
        VersionRow approvedVersion = all.stream().filter(v -> "APPROVED".equals(v.status())).findFirst().orElse(null);
        String stage = approvedEstimate == null ? "ESTIMATE_NOT_APPROVED"
                : latest == null ? "NOT_STARTED"
                : switch (latest.status()) {
                    case "GENERATING" -> "GENERATING";
                    case "FAILED" -> "FAILED";
                    case "IN_REVIEW" -> "IN_REVIEW";
                    case "APPROVED" -> "APPROVED";
                    default -> approvedVersion != null ? "APPROVED" : "NOT_STARTED";
                };
        VersionRow shown = a.insider() ? latest : approvedVersion;
        if (shown == null) {
            String blocked = approvedEstimate != null && latest == null && !a.estimator()
                    ? "Waiting for an Estimator of the project to start the delivery plan." : null;
            ReviewV review = new ReviewV(stage, a.estimator(), a.approver(), a.estimator() && approvedEstimate != null
                    && latest == null, false, false, false, false, List.of(), List.of(), null, null, null, false, blocked);
            return new PlanState(requirementId, false, a.insider(), review, null, List.of(),
                    a.estimator() ? settingsMap(defaultSettings(a.projectId(), approvedEstimate)) : null);
        }
        List<DecisionRow> decisions = plans.decisions(shown.id());
        DecisionRow approved = first(decisions, "APPROVED");
        VersionV version = versionView(shown, decisions, a.insider());
        boolean inReview = "IN_REVIEW".equals(shown.status());
        boolean open = all.stream().anyMatch(v -> Set.of("GENERATING", "IN_REVIEW", "FAILED").contains(v.status()));
        List<String> unaccepted = version.stories().stream()
                .filter(s -> !s.flags().isEmpty() && !s.accepted() && s.edits().isEmpty()).map(StoryV::storyKey).toList();
        List<String> gaps = version.gaps().stream().filter(g -> !g.accepted()).map(GapV::key).toList();
        boolean outdated = approvedEstimate != null && !approvedEstimate.equals(shown.estimateVersionId());
        String blocked = inReview && (!unaccepted.isEmpty() || !gaps.isEmpty())
                ? (unaccepted.isEmpty() ? "" : unaccepted.size() + " flagged story(ies)")
                + (!unaccepted.isEmpty() && !gaps.isEmpty() ? " and " : "")
                + (gaps.isEmpty() ? "" : gaps.size() + " planning gap(s)")
                + " need an Estimator's decision before the plan can be approved." : null;
        ReviewV review = new ReviewV(stage, a.estimator(), a.approver(),
                a.estimator() && approvedEstimate != null && !open,
                a.estimator() && "FAILED".equals(shown.status()) && shown.attempts() < RETRIES,
                inReview && a.estimator(), inReview && a.approver() && unaccepted.isEmpty() && gaps.isEmpty(),
                inReview && a.insider(), unaccepted, gaps,
                approved == null ? null : approved.decidedByName(), approved == null ? null : approved.decidedAt(),
                approved == null ? null : approved.note(), outdated, blocked);
        audit.record(AuditAction.PLAN_VIEWED, "PLAN_VERSION", shown.id(), AuditResult.SUCCESS);
        List<HistoryV> history = new ArrayList<>();
        if (a.insider()) {
            for (VersionRow v : all) {
                DecisionRow ok = first(v.id().equals(shown.id()) ? decisions : plans.decisions(v.id()), "APPROVED");
                history.add(new HistoryV(v.versionNo(), v.status(), v.createdAt(), v.changeRequest(),
                        ok == null ? null : ok.decidedByName(), ok == null ? null : ok.decidedAt()));
            }
        }
        return new PlanState(requirementId, true, a.insider(), review, version, history,
                a.estimator() ? settingsMap(defaultSettings(a.projectId(), approvedEstimate)) : null);
    }

    private static DecisionRow first(List<DecisionRow> decisions, String kind) {
        return decisions.stream().filter(d -> kind.equals(d.kind())).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private VersionV versionView(VersionRow v, List<DecisionRow> decisions, boolean insider) {
        Set<String> acceptedFlags = new HashSet<>();
        Set<String> acceptedGaps = new HashSet<>();
        for (DecisionRow d : decisions) {
            if ("FLAG_ACCEPTED".equals(d.kind())) {
                acceptedFlags.add(d.itemKey());
            } else if ("GAP_ACCEPTED".equals(d.kind())) {
                acceptedGaps.add(d.itemKey());
            }
        }
        Map<String, List<EditV>> edits = new LinkedHashMap<>();
        for (EditRow e : plans.edits(v.id())) {
            edits.computeIfAbsent(e.storyKey(), k -> new ArrayList<>()).add(new EditV(
                    json.readValue(e.changesJson(), LinkedHashMap.class), e.reason(), e.editedByName(), e.editedAt()));
        }
        PlanCalculator.Settings settings = settings(v.settingsJson());
        List<PlanCalculator.Schedulable> schedulable = new ArrayList<>();
        List<StoryRow> rows = plans.stories(v.id());
        Map<String, Object[]> effective = new LinkedHashMap<>();
        for (StoryRow s : rows) {
            String title = s.title();
            List<String> criteria = strings(s.acceptanceCriteriaJson());
            String priority = s.priority();
            Integer pinned = null;
            for (EditV e : edits.getOrDefault(s.storyKey(), List.of())) {
                if (e.changes().get("title") instanceof String t) {
                    title = t;
                }
                if (e.changes().get("acceptanceCriteria") instanceof List<?> l) {
                    criteria = l.stream().map(String::valueOf).toList();
                }
                if (e.changes().get("priority") instanceof String p) {
                    priority = p;
                }
                if (e.changes().get("sprint") instanceof Number n) {
                    pinned = n.intValue();
                }
            }
            effective.put(s.storyKey(), new Object[] {title, criteria, priority, pinned});
            schedulable.add(new PlanCalculator.Schedulable(s.storyKey(), s.title(), s.role(), priority,
                    s.effortDays().doubleValue(), strings(s.dependsOnJson()), pinned, s.ordinal()));
        }
        PlanCalculator.Schedule schedule = PlanCalculator.schedule(schedulable, settings);
        List<StoryV> stories = new ArrayList<>();
        Map<String, Integer> position = new LinkedHashMap<>();
        for (int i = 0; i < schedule.order().size(); i++) {
            position.put(schedule.order().get(i), i);
        }
        for (StoryRow s : rows) {
            Object[] e = effective.get(s.storyKey());
            List<String> flags = new ArrayList<>(strings(s.flagsJson()));
            flags.addAll(schedule.flags().getOrDefault(s.storyKey(), List.of()));
            stories.add(new StoryV(s.storyKey(), s.ordinal(), s.epicKey(), (String) e[0], (List<String>) e[1],
                    insider ? strings(s.estimateRefsJson()) : List.of(), strings(s.brdRefsJson()), s.role(),
                    (String) e[2], strings(s.dependsOnJson()), s.effortDays().doubleValue(),
                    schedule.sprintOf().get(s.storyKey()), e[3] != null, flags, acceptedFlags.contains(s.storyKey()),
                    insider ? edits.getOrDefault(s.storyKey(), List.of()) : List.of()));
        }
        stories.sort(java.util.Comparator.comparingInt(s -> position.getOrDefault(s.storyKey(), Integer.MAX_VALUE)));
        List<EpicV> epics = new ArrayList<>();
        for (EpicRow e : plans.epics(v.id())) {
            List<StoryV> in = stories.stream().filter(s -> s.epicKey().equals(e.epicKey())).toList();
            epics.add(new EpicV(e.epicKey(), e.title(), e.goal(), in.size(),
                    EstimateCalculator.round(in.stream().mapToDouble(StoryV::effortDays).sum())));
        }
        List<SprintV> sprints = schedule.sprints().stream().map(sp -> new SprintV(sp.number(), sp.start(), sp.end(),
                sp.load(), sp.capacity(), sp.overCapacity(),
                EstimateCalculator.round(sp.load().values().stream().mapToDouble(Double::doubleValue).sum()))).toList();
        double total = EstimateCalculator.round(stories.stream().mapToDouble(StoryV::effortDays).sum());
        // Gaps and the estimate total need the estimate's content: Estimators and BRD Approvers only.
        List<GapV> gaps = new ArrayList<>();
        Double estimateTotal = null;
        int estimateNo = estimates.find(v.estimateVersionId(), false).map(EstimateRepository.VersionRow::versionNo)
                .orElse(0);
        if (insider && !"GENERATING".equals(v.status()) && !"FAILED".equals(v.status())) {
            List<EstimateRepository.ItemRow> work = estimates.items(v.estimateVersionId());
            Map<String, double[]> fig = PlanGenerationHandler.effective(work, estimates.adjustments(v.estimateVersionId()));
            double sum = 0;
            Set<String> planned = new LinkedHashSet<>();
            rows.forEach(s -> planned.addAll(strings(s.estimateRefsJson())));
            for (EstimateRepository.ItemRow w : work) {
                double[] f = fig.get(w.itemKey());
                sum += (f[0] + 4 * f[1] + f[2]) / 6;
                if (!planned.contains(w.itemKey())) {
                    gaps.add(new GapV(w.itemKey(), "ESTIMATE_ITEM", w.title(), acceptedGaps.contains(w.itemKey())));
                }
            }
            estimateTotal = EstimateCalculator.round(sum);
            EstimateRepository.VersionRow est = estimates.find(v.estimateVersionId(), false).orElseThrow();
            Set<String> traced = new LinkedHashSet<>();
            rows.forEach(s -> traced.addAll(strings(s.brdRefsJson())));
            for (Map.Entry<String, String> criterion : brdAcceptanceCriteria(est.tddVersionId()).entrySet()) {
                if (!traced.contains(criterion.getKey())) {
                    gaps.add(new GapV(criterion.getKey(), "BRD_CRITERION", criterion.getValue(),
                            acceptedGaps.contains(criterion.getKey())));
                }
            }
        }
        return new VersionV(v.id(), v.versionNo(), v.status(), estimateNo, v.contentHash(), reviewHash(v, stories),
                v.changeRequest(), v.failureCode(), v.attempts(), v.createdAt(), settingsMap(v.settingsJson()),
                strings(v.risksJson()), epics, stories, sprints, gaps, total, estimateTotal, schedule.notes());
    }

    /** The approved BRD's acceptance criteria (item key to statement) behind the design the estimate rests on. */
    private Map<String, String> brdAcceptanceCriteria(UUID tddVersionId) {
        Map<String, String> out = new LinkedHashMap<>();
        UUID brdVersion = estimates.brdVersionOfDesign(tddVersionId).orElse(null);
        if (brdVersion == null) {
            return out;
        }
        for (BrdViews.ItemView i : reviews.view(brdVersion, false).items()) {
            if ("ACCEPTANCE_CRITERIA".equals(i.section())) {
                out.put(i.itemKey(), i.statement());
            }
        }
        return out;
    }

    private String reviewHash(VersionRow v, List<StoryV> stories) {
        if (v.contentHash() == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(v.contentHash());
        for (StoryV s : stories) {
            text.append('|').append(s.storyKey()).append(':').append(s.title()).append('/').append(s.priority())
                    .append('/').append(s.sprint()).append('/').append(String.join(";", s.acceptanceCriteria()));
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

    /** Project defaults, else the approved estimate's team and calendar, two-week sprints from next Monday. */
    String defaultSettings(UUID projectId, UUID approvedEstimate) {
        String saved = plans.defaults(projectId).orElse(null);
        if (saved != null) {
            return saved;
        }
        Map<String, Object> s = new LinkedHashMap<>();
        Map<String, Object> team = new LinkedHashMap<>();
        int days = 5;
        if (approvedEstimate != null) {
            var est = estimates.find(approvedEstimate, false).orElse(null);
            if (est != null) {
                JsonNode node = json.readTree(est.settingsJson());
                node.path("team").properties().forEach(e -> team.put(e.getKey(), e.getValue().asInt(0)));
                days = node.path("daysPerWeek").asInt(5);
            }
        }
        s.put("sprintWeeks", 2);
        s.put("startDate", LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toString());
        s.put("daysPerWeek", days);
        s.put("team", team);
        return json.writeValueAsString(s);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> settingsMap(String settingsJson) {
        return settingsJson == null ? null : json.readValue(settingsJson, LinkedHashMap.class);
    }

    PlanCalculator.Settings settings(String settingsJson) {
        JsonNode node = json.readTree(settingsJson);
        Map<String, Integer> team = new LinkedHashMap<>();
        node.path("team").properties().forEach(e -> team.put(e.getKey(), e.getValue().asInt(0)));
        LocalDate start;
        try {
            start = LocalDate.parse(node.path("startDate").asString(""));
        } catch (DateTimeParseException e) {
            start = LocalDate.now();
        }
        return new PlanCalculator.Settings(node.path("sprintWeeks").asInt(2), start, node.path("daysPerWeek").asInt(5),
                team);
    }

    String validSettings(Map<String, Object> input, String fallback) {
        if (input == null) {
            return fallback;
        }
        List<FieldError> errors = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        int weeks = input.get("sprintWeeks") instanceof Number n ? n.intValue() : 2;
        if (weeks < 1 || weeks > 4) {
            errors.add(new FieldError("sprintWeeks", "RANGE"));
        }
        out.put("sprintWeeks", weeks);
        String start = String.valueOf(input.get("startDate"));
        try {
            LocalDate.parse(start);
        } catch (DateTimeParseException e) {
            errors.add(new FieldError("startDate", "DATE"));
        }
        out.put("startDate", start);
        int days = input.get("daysPerWeek") instanceof Number n ? n.intValue() : 5;
        if (days < 1 || days > 7) {
            errors.add(new FieldError("daysPerWeek", "RANGE"));
        }
        out.put("daysPerWeek", days);
        Map<String, Integer> team = new LinkedHashMap<>();
        if (input.get("team") instanceof Map<?, ?> t) {
            for (Map.Entry<?, ?> e : t.entrySet()) {
                String role = String.valueOf(e.getKey());
                Integer n = e.getValue() instanceof Number num ? num.intValue() : null;
                if (!EstimateService.ROLES.contains(role) || n == null || n < 0 || n > 50) {
                    errors.add(new FieldError("team." + role, "INVALID"));
                } else if (n > 0) {
                    team.put(role, n);
                }
            }
        }
        out.put("team", team);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return json.writeValueAsString(out);
    }

    // --- actions ------------------------------------------------------------------------------------------------

    @Transactional
    public PlanState start(UUID requirementId, Map<String, Object> settingsInput) {
        Access a = access(requirementId);
        requireEstimator(a, "An Estimator of the project starts the delivery plan.");
        UUID estimate = plans.approvedEstimate(requirementId).orElseThrow(() ->
                ApiException.conflict("ESTIMATE_NOT_APPROVED", "The delivery plan starts from an approved estimate."));
        if (plans.open(requirementId, true).isPresent()) {
            throw ApiException.conflict("PLAN_IN_PROGRESS", "A delivery plan is already in progress.");
        }
        String settings = validSettings(settingsInput, defaultSettings(a.projectId(), estimate));
        plans.saveDefaults(TenantContext.requireTenant(), a.projectId(), settings, a.me());
        UUID id = plans.insert(TenantContext.requireTenant(), a.projectId(), requirementId, estimate, settings, null,
                a.me());
        request(requirementId, id, 1);
        return state(requirementId);
    }

    @Transactional
    public PlanState retry(UUID requirementId) {
        Access a = access(requirementId);
        requireEstimator(a, "An Estimator of the project retries the delivery plan.");
        VersionRow v = plans.open(requirementId, true).filter(x -> "FAILED".equals(x.status()))
                .orElseThrow(() -> ApiException.conflict("PLAN_NOT_FAILED", "There is no failed plan to retry."));
        if (v.attempts() >= RETRIES) {
            throw ApiException.conflict("PLAN_RETRIES_USED", "The plan was tried " + RETRIES
                    + " times. Contact Garuda support.");
        }
        plans.retry(v.id());
        request(requirementId, v.id(), v.attempts() + 1);
        return state(requirementId);
    }

    private void request(UUID requirementId, UUID versionId, int attempt) {
        outbox.publish("REQUIREMENT", requirementId, EventTypes.PLAN_REQUESTED,
                Map.of("requirementId", requirementId.toString(), "planVersionId", versionId.toString(),
                        "attempt", attempt));
        audit.record(AuditAction.PLAN_REQUESTED, "PLAN_VERSION", versionId, AuditResult.SUCCESS);
    }

    @Transactional
    public PlanState acceptFlag(UUID requirementId, String storyKey) {
        Access a = access(requirementId);
        requireEstimator(a, "An Estimator decides flagged stories.");
        VersionRow v = inReview(requirementId);
        if (plans.stories(v.id()).stream().noneMatch(s -> s.storyKey().equals(storyKey))) {
            throw ApiException.notFound();
        }
        decideOnce(a, requirementId, v, "FLAG_ACCEPTED", storyKey);
        return state(requirementId);
    }

    @Transactional
    public PlanState acceptGap(UUID requirementId, String key) {
        Access a = access(requirementId);
        requireEstimator(a, "An Estimator decides planning gaps.");
        VersionRow v = inReview(requirementId);
        if (!key.matches("^[a-z0-9-]{1,100}$")) {
            throw ApiException.notFound();
        }
        decideOnce(a, requirementId, v, "GAP_ACCEPTED", key);
        return state(requirementId);
    }

    private void decideOnce(Access a, UUID requirementId, VersionRow v, String kind, String key) {
        if (plans.decisions(v.id()).stream().noneMatch(d -> kind.equals(d.kind()) && key.equals(d.itemKey()))) {
            plans.insertDecision(TenantContext.requireTenant(), a.projectId(), requirementId, v.id(), kind, key, null,
                    a.me());
            audit.record(AuditAction.PLAN_DECIDED, "PLAN_VERSION", v.id(), AuditResult.SUCCESS);
        }
    }

    /** An Estimator edits a story's title, acceptance criteria, priority or sprint, with a reason. */
    @Transactional
    public PlanState edit(UUID requirementId, String storyKey, Map<String, Object> changes, String reason) {
        Access a = access(requirementId);
        requireEstimator(a, "An Estimator of the project edits the delivery plan.");
        VersionRow v = inReview(requirementId);
        List<FieldError> errors = new ArrayList<>();
        Map<String, Object> clean = new LinkedHashMap<>();
        if (changes != null) {
            if (changes.get("title") instanceof String t) {
                if (t.isBlank() || t.length() > 300) {
                    errors.add(new FieldError("title", "LENGTH"));
                } else {
                    clean.put("title", t.strip());
                }
            }
            if (changes.get("acceptanceCriteria") instanceof List<?> l) {
                List<String> criteria = l.stream().map(String::valueOf).map(String::strip).filter(c -> !c.isEmpty())
                        .toList();
                if (criteria.isEmpty() || criteria.size() > 12 || criteria.stream().anyMatch(c -> c.length() > 400)) {
                    errors.add(new FieldError("acceptanceCriteria", "INVALID"));
                } else {
                    clean.put("acceptanceCriteria", criteria);
                }
            }
            if (changes.get("priority") instanceof String p) {
                if (!Set.of("MUST", "SHOULD", "COULD").contains(p)) {
                    errors.add(new FieldError("priority", "INVALID"));
                } else {
                    clean.put("priority", p);
                }
            }
            if (changes.get("sprint") instanceof Number n) {
                if (n.intValue() < 1 || n.intValue() > 200) {
                    errors.add(new FieldError("sprint", "RANGE"));
                } else {
                    clean.put("sprint", n.intValue());
                }
            }
        }
        if (clean.isEmpty() && errors.isEmpty()) {
            errors.add(new FieldError("changes", "EMPTY"));
        }
        if (reason == null || reason.strip().length() < 10 || reason.length() > 1000) {
            errors.add(new FieldError("reason", "LENGTH"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        if (plans.stories(v.id()).stream().noneMatch(s -> s.storyKey().equals(storyKey))) {
            throw ApiException.notFound();
        }
        plans.insertEdit(TenantContext.requireTenant(), a.projectId(), v.id(), storyKey, json.writeValueAsString(clean),
                reason.strip(), a.me());
        audit.record(AuditAction.PLAN_EDITED, "PLAN_VERSION", v.id(), AuditResult.SUCCESS);
        return state(requirementId);
    }

    @Transactional
    public PlanState approve(UUID requirementId, String expectedReviewHash, boolean confirmedAlone) {
        Access a = access(requirementId);
        if (!a.approver()) {
            throw forbidden("A BRD Approver of the project approves the delivery plan.");
        }
        VersionRow v = inReview(requirementId);
        PlanState current = state(requirementId);
        if (expectedReviewHash != null && !expectedReviewHash.equals(current.version().reviewHash())) {
            throw ApiException.conflict("VERSION_CHANGED", "The plan changed since you opened it. Reload it.");
        }
        if (!current.review().unacceptedFlags().isEmpty() || !current.review().openGaps().isEmpty()) {
            throw ApiException.conflict("REVIEW_INCOMPLETE",
                    "An Estimator must decide every flagged story and planning gap first.");
        }
        if (!confirmedAlone) {
            throw ApiException.conflict("CONFIRMATION_REQUIRED",
                    "Confirm that you approve this plan on your own, without a second reviewer.");
        }
        for (VersionRow old : plans.versions(requirementId)) {
            if ("APPROVED".equals(old.status())) {
                plans.setStatus(old.id(), "SUPERSEDED");
            }
        }
        plans.insertDecision(TenantContext.requireTenant(), a.projectId(), requirementId, v.id(), "APPROVED", null,
                SINGLE_APPROVAL_NOTE, a.me());
        plans.setStatus(v.id(), "APPROVED");
        for (UUID user : new LinkedHashSet<>(estimates.membersWithRole(a.projectId(), "ESTIMATOR"))) {
            reviews.notify(TenantContext.requireTenant(), user, "PLAN_APPROVED", requirementId);
        }
        audit.record(AuditAction.PLAN_APPROVED, "PLAN_VERSION", v.id(), AuditResult.SUCCESS);
        return state(requirementId);
    }

    @Transactional
    public PlanState requestChanges(UUID requirementId, String text) {
        if (text == null || text.strip().length() < 10 || text.length() > 5000) {
            throw ApiException.validation(List.of(new FieldError("text", "LENGTH")));
        }
        Access a = access(requirementId);
        if (!a.insider()) {
            throw forbidden("An Estimator or a BRD Approver requests changes.");
        }
        VersionRow v = inReview(requirementId);
        plans.insertDecision(TenantContext.requireTenant(), a.projectId(), requirementId, v.id(), "CHANGES_REQUESTED",
                null, text.strip(), a.me());
        plans.setStatus(v.id(), "SUPERSEDED");
        UUID estimate = plans.approvedEstimate(requirementId).orElseThrow(() ->
                ApiException.conflict("ESTIMATE_NOT_APPROVED", "The delivery plan starts from an approved estimate."));
        UUID next = plans.insert(TenantContext.requireTenant(), a.projectId(), requirementId, estimate, v.settingsJson(),
                text.strip(), a.me());
        audit.record(AuditAction.PLAN_DECIDED, "PLAN_VERSION", v.id(), AuditResult.SUCCESS);
        request(requirementId, next, 1);
        return state(requirementId);
    }

    private VersionRow inReview(UUID requirementId) {
        return plans.open(requirementId, true).filter(v -> "IN_REVIEW".equals(v.status()))
                .orElseThrow(() -> ApiException.conflict("PLAN_NOT_IN_REVIEW",
                        "This delivery plan is not waiting for a review decision."));
    }

    private static void requireEstimator(Access a, String message) {
        if (!a.estimator()) {
            throw forbidden(message);
        }
    }

    private static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "PLAN_DECISION_NOT_ALLOWED", message);
    }

    // --- export -------------------------------------------------------------------------------------------------

    /** The approved plan (or a given approved version an insider asks for). */
    @Transactional
    public VersionV approvedVersion(UUID requirementId, Integer versionNo) {
        Access a = access(requirementId);
        VersionRow v = plans.versions(requirementId).stream()
                .filter(x -> versionNo == null || !a.insider() ? "APPROVED".equals(x.status()) : x.versionNo() == versionNo)
                .filter(x -> first(plans.decisions(x.id()), "APPROVED") != null)
                .findFirst().orElseThrow(() -> ApiException.conflict("PLAN_NOT_APPROVED",
                        "Only an approved delivery plan can be downloaded."));
        audit.record(AuditAction.PLAN_EXPORTED, "PLAN_VERSION", v.id(), AuditResult.SUCCESS);
        return versionView(v, plans.decisions(v.id()), a.insider());
    }

    static String csv(VersionV v) {
        Map<String, String> epicTitle = new LinkedHashMap<>();
        v.epics().forEach(e -> epicTitle.put(e.epicKey(), e.title()));
        StringBuilder out = new StringBuilder("Sprint,Sprint start,Epic,Story,Role,Priority,Effort days,"
                + "Acceptance criteria,Depends on\r\n");
        Map<Integer, LocalDate> starts = new LinkedHashMap<>();
        v.sprints().forEach(s -> starts.put(s.number(), s.start()));
        for (StoryV s : v.stories()) {
            out.append(s.sprint() == null ? "" : s.sprint()).append(',')
                    .append(s.sprint() == null || starts.get(s.sprint()) == null ? "" : starts.get(s.sprint())).append(',')
                    .append(EstimateService.cell(epicTitle.getOrDefault(s.epicKey(), ""))).append(',')
                    .append(EstimateService.cell(s.title())).append(',')
                    .append(EstimateService.ROLE_LABELS.getOrDefault(s.role(), s.role())).append(',')
                    .append(s.priority()).append(',').append(s.effortDays()).append(',')
                    .append(EstimateService.cell(String.join(" | ", s.acceptanceCriteria()))).append(',')
                    .append(EstimateService.cell(String.join("; ", s.dependsOn()))).append("\r\n");
        }
        return out.toString();
    }

    @Transactional(readOnly = true)
    public BrdDocument document(UUID requirementId, VersionV view, ZoneId zone) {
        DateTimeFormatter when = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH).withZone(zone);
        DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        BrdDocument.Header h = reviews.exportHeader(requirementId);
        Map<String, String> epicTitle = new LinkedHashMap<>();
        view.epics().forEach(e -> epicTitle.put(e.epicKey(), e.title()));
        List<BrdDocument.Section> sections = new ArrayList<>();
        sections.add(new BrdDocument.Section("Epics", view.epics().stream().map(e -> new BrdDocument.Entry("Epic",
                e.title() + ". " + e.goal(), e.stories() + " stories, " + e.effortDays() + " person-days", List.of()))
                .toList()));
        for (SprintV sp : view.sprints()) {
            List<BrdDocument.Entry> entries = view.stories().stream()
                    .filter(s -> Integer.valueOf(sp.number()).equals(s.sprint()))
                    .map(s -> new BrdDocument.Entry(s.priority(), s.title(), EstimateService.ROLE_LABELS.getOrDefault(
                            s.role(), s.role()) + " · " + s.effortDays() + " days · " + epicTitle.getOrDefault(s.epicKey(), ""),
                            s.acceptanceCriteria())).toList();
            sections.add(new BrdDocument.Section("Sprint " + sp.number() + " (" + day.format(sp.start()) + " to "
                    + day.format(sp.end()) + ")", entries));
        }
        if (!view.risks().isEmpty()) {
            sections.add(new BrdDocument.Section("Delivery risks", view.risks().stream()
                    .map(r -> new BrdDocument.Entry("Risk", r, "", List.of())).toList()));
        }
        List<BrdDocument.Field> approval = new ArrayList<>();
        for (DecisionRow d : plans.decisions(view.id())) {
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
                new BrdDocument.Field("Plan version", view.versionNo() + ", produced " + when.format(view.createdAt())),
                new BrdDocument.Field("Based on", "Approved estimate version " + view.estimateVersionNo()),
                new BrdDocument.Field("Exported", exported + " by " + by));
        String summary = view.stories().size() + " stories in " + view.epics().size() + " epics over "
                + view.sprints().size() + " sprints, " + view.totalEffortDays() + " person-days of expected effort.";
        return new BrdDocument(BrdDocument.DocKind.PLAN, cover, h.organisation(), h.project(), h.requirementKey(),
                h.title(), List.of(), view.versionNo(), "APPROVED".equals(view.status()) ? "Approved"
                : "Approved, replaced by a later version", view.reviewHash(), when.format(view.createdAt()), null,
                List.of(), summary, sections, List.of(), approval, exported, by);
    }
}
