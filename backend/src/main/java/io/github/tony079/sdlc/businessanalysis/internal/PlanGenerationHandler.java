package io.github.tony079.sdlc.businessanalysis.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.VersionView;
import io.github.tony079.sdlc.businessanalysis.internal.PlanRepository.EpicRow;
import io.github.tony079.sdlc.businessanalysis.internal.PlanRepository.StoryRow;
import io.github.tony079.sdlc.businessanalysis.internal.PlanRepository.VersionRow;
import io.github.tony079.sdlc.modelgateway.api.DataBlock;
import io.github.tony079.sdlc.modelgateway.api.ModelException;
import io.github.tony079.sdlc.modelgateway.api.ModelGateway;
import io.github.tony079.sdlc.modelgateway.api.ModelRequest;
import io.github.tony079.sdlc.modelgateway.api.ModelResponse;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.requirement.api.RequirementInputs;
import io.github.tony079.sdlc.requirement.api.RequirementView.Content;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PLAN_GENERATION (GD-34): the Planning agent. Execute (outside a transaction): reads the approved estimate (with its
 * adjustments), the design and BRD it rests on, and the requirement; asks the model for epics and stories. The engine
 * drops unknown references, gives each story its effort from the approved estimate and flags gaps. Commit: stores the
 * plan (IN_REVIEW) and tells the project's Estimators. Sprints are laid out when the plan is read, from its settings.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class PlanGenerationHandler implements JobHandler<PlanGenerationHandler.Outcome> {

    static final String JOB_TYPE = "PLAN_GENERATION";
    static final int PREVIOUS_MAX_CHARS = 80_000;

    record Outcome(UUID versionId, String risksJson, List<EpicRow> epics, List<StoryRow> stories, String contentHash,
                   String provider, String modelId) {
    }

    private record Inputs(VersionRow version, List<EstimateRepository.ItemRow> estimate,
                          Map<String, double[]> effective, int estimateVersionNo, List<DesignRepository.ItemRow> design,
                          VersionView brd, Content requirement, String previous, Integer previousNo) {
    }

    private final ModelGateway gateway;
    private final PlanRepository plans;
    private final EstimateRepository estimates;
    private final DesignRepository designs;
    private final ReviewRepository reviews;
    private final RequirementInputs requirements;
    private final AuditWriter audit;
    private final JsonMapper json;
    private final TransactionTemplate readOnly;
    private final ActivityLog activity;
    private final BaAnalysisHandler.Describer describer = new BaAnalysisHandler.Describer();

    PlanGenerationHandler(ActivityLog activity, ModelGateway gateway, PlanRepository plans, EstimateRepository estimates,
                          DesignRepository designs, ReviewRepository reviews, RequirementInputs requirements,
                          AuditWriter audit, JsonMapper json, PlatformTransactionManager transactionManager) {
        this.activity = activity;
        this.gateway = gateway;
        this.plans = plans;
        this.estimates = estimates;
        this.designs = designs;
        this.reviews = reviews;
        this.requirements = requirements;
        this.audit = audit;
        this.json = json;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID versionId = BaAnalysisHandler.uuid(job, "planVersionId");
        Inputs inputs = readOnly.execute(s -> load(versionId));
        if (inputs == null) {
            return null;
        }
        UUID requirementId = inputs.version().requirementId();
        ActivityLog.Stage stage = ActivityLog.Stage.PLAN;
        activity.step(requirementId, stage, versionId, "Started the delivery plan" + (job.attemptNo() > 1
                ? " (attempt " + job.attemptNo() + ")" : "") + ": reading the approved estimate version "
                + inputs.estimateVersionNo() + " (" + ActivityLog.count(inputs.estimate().size(), "work item") + ").");
        try {
            List<DataBlock> data = new ArrayList<>();
            data.add(ContextAssembler.requirement(inputs.requirement()));
            data.add(DesignGenerationHandler.brdBlock(inputs.brd()));
            data.add(EstimateGenerationHandler.designBlock(inputs.design(), 0));
            data.add(estimateBlock(inputs.estimate(), inputs.effective(), inputs.estimateVersionNo()));
            if (inputs.version().changeRequest() != null) {
                data.add(new DataBlock("change request for this plan version", inputs.version().changeRequest()));
            }
            if (inputs.previous() != null) {
                data.add(new DataBlock("previous plan version " + inputs.previousNo()
                        + " (revise it: keep each epic and story unless the change request requires a change)",
                        inputs.previous()));
                activity.step(requirementId, stage, versionId, "Revising plan version " + inputs.previousNo()
                        + " with the change request; everything else is kept as it was.");
            }
            activity.step(requirementId, stage, versionId, "Writing epics and user stories with acceptance criteria. "
                    + "This takes from about a minute to several minutes for a large estimate.");
            ModelResponse<PlanContracts.Proposal> response = gateway.generate(new ModelRequest<>(BaPrompts.PLAN_BACKLOG,
                    data, PlanContracts.Proposal.class, requirementId, BaAnalysisHandler.LONG_OUTPUT_TOKENS,
                    BaAnalysisHandler.LONG_CALL_TIMEOUT).withProgress(DraftWatcher.plan(activity, requirementId,
                    versionId, json)));
            describer.repaired(activity, requirementId, stage, versionId, response);
            List<PlanCalculator.WorkItem> work = new ArrayList<>();
            for (EstimateRepository.ItemRow i : inputs.estimate()) {
                double[] f = inputs.effective().get(i.itemKey());
                work.add(new PlanCalculator.WorkItem(i.itemKey(), i.role(), (f[0] + 4 * f[1] + f[2]) / 6));
            }
            Set<String> designKeys = new LinkedHashSet<>();
            inputs.design().forEach(d -> designKeys.add(d.itemKey()));
            Set<String> brdKeys = new LinkedHashSet<>();
            inputs.brd().items().forEach(b -> brdKeys.add(b.itemKey()));
            PlanCalculator.Prepared prepared = PlanCalculator.prepare(response.output(), work, designKeys, brdKeys);
            List<EpicRow> epics = new ArrayList<>();
            int n = 0;
            for (PlanCalculator.PreparedEpic e : prepared.epics()) {
                epics.add(new EpicRow(++n, e.epicKey(), e.title(), e.goal(), json.writeValueAsString(e.designRefs())));
            }
            List<StoryRow> stories = new ArrayList<>();
            n = 0;
            for (PlanCalculator.PreparedStory s : prepared.stories()) {
                stories.add(new StoryRow(++n, s.storyKey(), s.epicKey(), s.title(),
                        json.writeValueAsString(s.acceptanceCriteria()), json.writeValueAsString(s.estimateRefs()),
                        json.writeValueAsString(s.brdRefs()), s.role(), s.priority(), json.writeValueAsString(s.dependsOn()),
                        BigDecimal.valueOf(s.effortDays()), json.writeValueAsString(s.flags())));
            }
            Set<String> planned = new LinkedHashSet<>();
            prepared.stories().forEach(s -> planned.addAll(s.estimateRefs()));
            long gaps = work.stream().filter(w -> !planned.contains(w.key())).count();
            activity.done(requirementId, stage, versionId, "The model returned " + ActivityLog.count(epics.size(), "epic")
                    + " and " + ActivityLog.count(stories.size(), "story") + "; "
                    + (gaps == 0 ? "every estimate work item is planned." : ActivityLog.count((int) gaps, "work item")
                    + " not planned yet."));
            String risks = json.writeValueAsString(response.output().risks());
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("epics", epics);
            canonical.put("stories", stories);
            canonical.put("risks", risks);
            return new Outcome(versionId, risks, epics, stories, InputSets.sha256(json.writeValueAsString(canonical)),
                    response.provider(), response.modelId());
        } catch (ModelException e) {
            activity.add(requirementId, stage, versionId, ActivityLog.Kind.FAIL, BaAnalysisHandler.describe(e));
            throw BaAnalysisHandler.failure(e);
        }
    }

    static DataBlock estimateBlock(List<EstimateRepository.ItemRow> items, Map<String, double[]> effective, int versionNo) {
        StringBuilder text = new StringBuilder();
        for (EstimateRepository.ItemRow i : items) {
            double[] f = effective.get(i.itemKey());
            text.append('[').append(i.itemKey()).append("] ").append(i.role()).append(" / ").append(i.phase()).append(": ")
                    .append(i.title()).append(" — ").append(i.detail()).append(" (").append(f[0]).append(" / ")
                    .append(f[1]).append(" / ").append(f[2]).append(" days; after: ").append(i.dependsOnJson())
                    .append(")\n");
        }
        return new DataBlock("approved estimate version " + versionNo + " (work item key, role / phase: title — detail); "
                + "every key must appear in estimateRefs of a story", text.toString());
    }

    /** Figures in effect per work item: the latest adjustment, else the proposal. */
    static Map<String, double[]> effective(List<EstimateRepository.ItemRow> items,
                                           List<EstimateRepository.AdjustmentRow> adjustments) {
        Map<String, double[]> out = new HashMap<>();
        for (EstimateRepository.ItemRow i : items) {
            out.put(i.itemKey(), new double[] {i.low().doubleValue(), i.likely().doubleValue(), i.high().doubleValue()});
        }
        for (EstimateRepository.AdjustmentRow a : adjustments) {
            if (out.containsKey(a.itemKey())) {
                out.put(a.itemKey(), new double[] {a.low().doubleValue(), a.likely().doubleValue(), a.high().doubleValue()});
            }
        }
        return out;
    }

    private Inputs load(UUID versionId) {
        VersionRow version = plans.find(versionId, false).orElse(null);
        if (version == null || !"GENERATING".equals(version.status())) {
            return null;
        }
        EstimateRepository.VersionRow estimate = estimates.find(version.estimateVersionId(), false).orElseThrow();
        List<EstimateRepository.ItemRow> items = estimates.items(estimate.id());
        DesignRepository.VersionRow design = designs.find(estimate.tddVersionId(), false).orElseThrow();
        VersionView brd = reviews.view(design.brdVersionId(), false);
        Content content = requirements.currentRevision(version.requirementId()).orElseThrow().content();
        String previous = null;
        Integer previousNo = null;
        if (version.changeRequest() != null) {
            VersionRow before = plans.versions(version.requirementId()).stream()
                    .filter(v -> v.versionNo() < version.versionNo() && v.contentHash() != null).findFirst().orElse(null);
            if (before != null) {
                StringBuilder text = new StringBuilder("Epics:\n");
                Map<String, String> epicTitles = new HashMap<>();
                for (EpicRow e : plans.epics(before.id())) {
                    epicTitles.put(e.epicKey(), e.title());
                    text.append("- ").append(e.title()).append(": ").append(e.goal()).append('\n');
                }
                text.append("\nStories:\n");
                for (StoryRow s : plans.stories(before.id())) {
                    text.append("- [").append(epicTitles.getOrDefault(s.epicKey(), "?")).append(" | ").append(s.role())
                            .append(" | ").append(s.priority()).append("] ").append(s.title()).append("; criteria ")
                            .append(s.acceptanceCriteriaJson()).append("; estimateRefs ").append(s.estimateRefsJson())
                            .append("; brdRefs ").append(s.brdRefsJson()).append("; dependsOn ").append(s.dependsOnJson())
                            .append('\n');
                }
                text.append("\nRisks: ").append(before.risksJson());
                previous = text.length() <= PREVIOUS_MAX_CHARS ? text.toString()
                        : text.substring(0, PREVIOUS_MAX_CHARS - 1) + "…";
                previousNo = before.versionNo();
            }
        }
        return new Inputs(version, items, effective(items, estimates.adjustmentFigures(estimate.id())), estimate.versionNo(),
                designs.items(design.id()), brd, content, previous, previousNo);
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        if (outcome == null) {
            return;
        }
        VersionRow version = plans.find(outcome.versionId(), true).orElse(null);
        if (version == null || !"GENERATING".equals(version.status())) {
            return;
        }
        for (EpicRow e : outcome.epics()) {
            plans.insertEpic(version.tenantId(), version.projectId(), version.id(), e);
        }
        for (StoryRow s : outcome.stories()) {
            plans.insertStory(version.tenantId(), version.projectId(), version.id(), s);
        }
        plans.complete(version.id(), outcome.risksJson(), outcome.contentHash(), outcome.provider(), outcome.modelId());
        for (UUID user : new LinkedHashSet<>(estimates.membersWithRole(version.projectId(), "ESTIMATOR"))) {
            reviews.notify(version.tenantId(), user, "PLAN_READY_FOR_REVIEW", version.requirementId());
        }
        audit.record(AuditAction.PLAN_REQUESTED, "PLAN_VERSION", version.id(), AuditResult.SUCCESS);
        activity.done(version.requirementId(), ActivityLog.Stage.PLAN, version.id(), "Plan version " + version.versionNo()
                + " ready for review: " + ActivityLog.count(outcome.stories().size(), "story") + ".");
    }
}
