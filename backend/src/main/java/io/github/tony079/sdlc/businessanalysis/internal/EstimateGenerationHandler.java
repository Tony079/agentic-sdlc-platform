package io.github.tony079.sdlc.businessanalysis.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
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
import io.github.tony079.sdlc.businessanalysis.internal.EstimateRepository.ItemRow;
import io.github.tony079.sdlc.businessanalysis.internal.EstimateRepository.VersionRow;
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
 * ESTIMATE_GENERATION (GD-33): the Estimation agent. Execute (outside a transaction): reads the approved design, the
 * BRD it was made from, the requirement, the client's answers and the frozen settings; asks the model for work items.
 * The engine clips and flags the figures and drops unknown design references. Commit: stores the items (IN_REVIEW) and
 * tells the project's Estimators. The model never computes totals, timelines or cost.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class EstimateGenerationHandler implements JobHandler<EstimateGenerationHandler.Outcome> {

    static final String JOB_TYPE = "ESTIMATE_GENERATION";
    static final int PREVIOUS_MAX_CHARS = 80_000;

    /** GD-35: settings of an agent-mode estimate (canonical JSON written by EstimateService.validSettings). */
    static final java.util.regex.Pattern AGENT_MODE = java.util.regex.Pattern.compile("\"deliveryMode\"\\s*:\\s*\"AGENT\"");

    record Outcome(UUID versionId, String assumptionsJson, String excludedJson, List<ItemRow> items,
                   String contentHash, String provider, String modelId) {
    }

    private record Inputs(VersionRow version, List<DesignRepository.ItemRow> design, int designVersionNo,
                          VersionView brd, Content requirement, List<Map<String, String>> answers, String previous,
                          Integer previousNo) {
    }

    private final ModelGateway gateway;
    private final EstimateRepository estimates;
    private final DesignRepository designs;
    private final ReviewRepository reviews;
    private final ClarificationRepository clarifications;
    private final RequirementInputs requirements;
    private final AuditWriter audit;
    private final JsonMapper json;
    private final TransactionTemplate readOnly;
    private final ActivityLog activity;
    private final BaAnalysisHandler.Describer describer = new BaAnalysisHandler.Describer();

    EstimateGenerationHandler(ActivityLog activity, ModelGateway gateway, EstimateRepository estimates,
                              DesignRepository designs, ReviewRepository reviews, ClarificationRepository clarifications,
                              RequirementInputs requirements, AuditWriter audit, JsonMapper json,
                              PlatformTransactionManager transactionManager) {
        this.activity = activity;
        this.gateway = gateway;
        this.estimates = estimates;
        this.designs = designs;
        this.reviews = reviews;
        this.clarifications = clarifications;
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
        UUID versionId = BaAnalysisHandler.uuid(job, "estimateVersionId");
        Inputs inputs = readOnly.execute(s -> load(versionId));
        if (inputs == null) {
            return null; // no longer generating: nothing to do
        }
        UUID requirementId = inputs.version().requirementId();
        ActivityLog.Stage stage = ActivityLog.Stage.ESTIMATE;
        activity.step(requirementId, stage, versionId, "Started the estimate" + (job.attemptNo() > 1
                ? " (attempt " + job.attemptNo() + ")" : "") + ": reading the approved design version "
                + inputs.designVersionNo() + " (" + ActivityLog.count(inputs.design().size(), "item") + ").");
        try {
            List<DataBlock> data = new ArrayList<>();
            data.add(ContextAssembler.requirement(inputs.requirement()));
            data.add(DesignGenerationHandler.brdBlock(inputs.brd()));
            data.add(designBlock(inputs.design(), inputs.designVersionNo()));
            data.add(new DataBlock("estimate settings (team, calendar, contingency; for context only, do not compute "
                    + "totals)", inputs.version().settingsJson()));
            if (!inputs.answers().isEmpty()) {
                data.add(new DataBlock("answered clarifications (question and the client's answer)",
                        json.writeValueAsString(inputs.answers())));
            }
            if (inputs.version().changeRequest() != null) {
                data.add(new DataBlock("change request for this estimate version", inputs.version().changeRequest()));
            }
            if (inputs.previous() != null) {
                data.add(new DataBlock("previous estimate version " + inputs.previousNo()
                        + " (revise it: keep each item unless the change request requires a change)", inputs.previous()));
                activity.step(requirementId, stage, versionId, "Revising estimate version " + inputs.previousNo()
                        + " with the change request; everything else is kept as it was.");
            }
            activity.step(requirementId, stage, versionId, "Breaking the design into work items with effort ranges. "
                    + "This takes from about a minute to several minutes for a large design.");
            boolean agentMode = AGENT_MODE.matcher(inputs.version().settingsJson()).find();
            Set<String> designKeys = new LinkedHashSet<>();
            inputs.design().forEach(i -> designKeys.add(i.itemKey()));
            List<EstimateCalculator.Prepared> prepared;
            ModelResponse<?> response;
            List<String> assumptionList;
            List<String> excludedList;
            if (agentMode) {
                // GD-35: coding agents build, people review; outside work is listed with its calendar wait.
                ModelResponse<EstimateContracts.AgentProposal> r = gateway.generate(new ModelRequest<>(
                        BaPrompts.EST_ITEMS_AGENT, data, EstimateContracts.AgentProposal.class, requirementId,
                        BaAnalysisHandler.LONG_OUTPUT_TOKENS, BaAnalysisHandler.LONG_CALL_TIMEOUT)
                        .withProgress(DraftWatcher.estimate(activity, requirementId, versionId, json)));
                response = r;
                prepared = EstimateCalculator.prepareAgent(r.output().items(), designKeys);
                assumptionList = r.output().assumptions();
                excludedList = r.output().excluded();
            } else {
                ModelResponse<EstimateContracts.Proposal> r = gateway.generate(new ModelRequest<>(
                        BaPrompts.EST_ITEMS, data, EstimateContracts.Proposal.class, requirementId,
                        BaAnalysisHandler.LONG_OUTPUT_TOKENS, BaAnalysisHandler.LONG_CALL_TIMEOUT)
                        .withProgress(DraftWatcher.estimate(activity, requirementId, versionId, json)));
                response = r;
                prepared = EstimateCalculator.prepare(r.output().items(), designKeys);
                assumptionList = r.output().assumptions();
                excludedList = r.output().excluded();
            }
            describer.repaired(activity, requirementId, stage, versionId, response);
            List<ItemRow> items = new ArrayList<>();
            int ordinal = 0;
            for (EstimateCalculator.Prepared p : prepared) {
                ordinal++;
                items.add(new ItemRow(ordinal, p.itemKey(), p.title(), p.detail(), p.role(), p.phase(),
                        json.writeValueAsString(p.designRefs()), BigDecimal.valueOf(p.low()),
                        BigDecimal.valueOf(p.likely()), BigDecimal.valueOf(p.high()),
                        json.writeValueAsString(p.assumptions()), p.risk(), json.writeValueAsString(p.dependsOn()),
                        json.writeValueAsString(p.flags()), p.kind(),
                        p.agentHours() == null ? null : BigDecimal.valueOf(p.agentHours()),
                        p.leadDays() == null ? null : BigDecimal.valueOf(p.leadDays())));
            }
            long flagged = prepared.stream().filter(p -> !p.flags().isEmpty()).count();
            Set<String> covered = new LinkedHashSet<>();
            prepared.forEach(p -> covered.addAll(p.designRefs()));
            long gaps = designKeys.stream().filter(k -> !covered.contains(k)).count();
            activity.done(requirementId, stage, versionId, "The model returned "
                    + ActivityLog.count(items.size(), "work item") + ". " + flagged + " flagged for review; "
                    + (gaps == 0 ? "every design item is covered." : ActivityLog.count((int) gaps, "design item")
                    + " not covered yet."));
            String assumptions = json.writeValueAsString(assumptionList);
            String excluded = json.writeValueAsString(excludedList);
            return new Outcome(versionId, assumptions, excluded, items, contentHash(items, assumptions, excluded),
                    response.provider(), response.modelId());
        } catch (ModelException e) {
            activity.add(requirementId, stage, versionId, ActivityLog.Kind.FAIL, BaAnalysisHandler.describe(e));
            throw BaAnalysisHandler.failure(e);
        }
    }

    /** The approved design, one line per item with its key (the keys work items reference). */
    static DataBlock designBlock(List<DesignRepository.ItemRow> items, int versionNo) {
        StringBuilder text = new StringBuilder();
        for (DesignRepository.ItemRow i : items) {
            text.append('[').append(i.itemKey()).append("] ").append(i.section()).append(" / ").append(i.kind())
                    .append(": ").append(i.title()).append(" — ").append(i.detail());
            if (i.component() != null) {
                text.append(" (component: ").append(i.component()).append(')');
            }
            text.append('\n');
        }
        return new DataBlock("approved technical design version " + versionNo
                + " (item key, section / kind: title — detail); every key must appear in designRefs of a work item",
                text.toString());
    }

    private Inputs load(UUID versionId) {
        VersionRow version = estimates.find(versionId, false).orElse(null);
        if (version == null || !"GENERATING".equals(version.status())) {
            return null;
        }
        DesignRepository.VersionRow design = designs.find(version.tddVersionId(), false).orElseThrow();
        VersionView brd = reviews.view(design.brdVersionId(), false);
        Content content = requirements.currentRevision(version.requirementId()).orElseThrow().content();
        String previous = null;
        Integer previousNo = null;
        if (version.changeRequest() != null) {
            VersionRow before = estimates.versions(version.requirementId()).stream()
                    .filter(v -> v.versionNo() < version.versionNo() && v.contentHash() != null).findFirst().orElse(null);
            if (before != null) {
                StringBuilder text = new StringBuilder();
                for (ItemRow i : estimates.items(before.id())) {
                    text.append("- [").append(i.role()).append(" | ").append(i.phase()).append("] ").append(i.title())
                            .append(": ").append(i.detail()).append(" (").append(i.low()).append(" / ").append(i.likely())
                            .append(" / ").append(i.high()).append(" days; designRefs ").append(i.designRefsJson())
                            .append("; dependsOn ").append(i.dependsOnJson()).append(")\n");
                }
                text.append("\nAssumptions: ").append(before.assumptionsJson()).append("\nExcluded: ")
                        .append(before.excludedJson());
                previous = text.length() <= PREVIOUS_MAX_CHARS ? text.toString()
                        : text.substring(0, PREVIOUS_MAX_CHARS - 1) + "…";
                previousNo = before.versionNo();
            }
        }
        return new Inputs(version, designs.items(design.id()), design.versionNo(), brd, content,
                clarifications.answeredInputs(version.requirementId()), previous, previousNo);
    }

    String contentHash(List<ItemRow> items, String assumptions, String excluded) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("items", items);
        canonical.put("assumptions", assumptions);
        canonical.put("excluded", excluded);
        return InputSets.sha256(json.writeValueAsString(canonical));
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        if (outcome == null) {
            return;
        }
        VersionRow version = estimates.find(outcome.versionId(), true).orElse(null);
        if (version == null || !"GENERATING".equals(version.status())) {
            return;
        }
        for (ItemRow item : outcome.items()) {
            estimates.insertItem(version.tenantId(), version.projectId(), version.id(), item);
        }
        estimates.complete(version.id(), outcome.assumptionsJson(), outcome.excludedJson(), outcome.contentHash(),
                outcome.provider(), outcome.modelId());
        Set<UUID> recipients = new LinkedHashSet<>(estimates.membersWithRole(version.projectId(), "ESTIMATOR"));
        for (UUID user : recipients) {
            reviews.notify(version.tenantId(), user, "ESTIMATE_READY_FOR_REVIEW", version.requirementId());
        }
        audit.record(AuditAction.ESTIMATE_REQUESTED, "ESTIMATE_VERSION", version.id(), AuditResult.SUCCESS);
        activity.done(version.requirementId(), ActivityLog.Stage.ESTIMATE, version.id(), "Estimate version "
                + version.versionNo() + " ready for review: " + ActivityLog.count(outcome.items().size(), "work item") + ".");
    }
}
