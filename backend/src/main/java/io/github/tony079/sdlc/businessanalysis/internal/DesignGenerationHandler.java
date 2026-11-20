package io.github.tony079.sdlc.businessanalysis.internal;

import java.io.IOException;
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
import io.github.tony079.sdlc.businessanalysis.internal.AnalysisRepository.InputSetRow;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Evidence;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.EvidenceType;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.ItemView;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.VersionView;
import io.github.tony079.sdlc.businessanalysis.internal.DesignRepository.ItemRow;
import io.github.tony079.sdlc.businessanalysis.internal.DesignRepository.VersionRow;
import io.github.tony079.sdlc.businessanalysis.internal.EvidenceVerifier.VerifiedEvidence;
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
import io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs;
import io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs.Inventory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TDD_GENERATION (GD-26): the Architect agent. Execute (outside a transaction): reads the approved BRD, the requirement,
 * the client's clarification answers and the code at the commits the BRD was analysed at; asks the model which files
 * to read, then for the design; verifies every citation against the pinned snapshot and drops BRD references that do
 * not exist. Commit: stores the verified design (IN_REVIEW) and notifies the author. The model decides nothing.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class DesignGenerationHandler implements JobHandler<DesignGenerationHandler.Outcome> {

    static final String JOB_TYPE = "TDD_GENERATION";
    static final int MAX_FILES = 20;

    record Outcome(UUID versionId, String summary, String openQuestionsJson, List<ItemRow> items, String contentHash,
                   String provider, String modelId) {
    }

    private record Inputs(VersionRow version, VersionView brd, Content requirement, List<Map<String, String>> answers,
                          InputSetRow inputSet, Inventory inventory) {
    }

    private final ModelGateway gateway;
    private final DesignRepository designs;
    private final ReviewRepository reviews;
    private final AnalysisRepository analysis;
    private final ClarificationRepository clarifications;
    private final RequirementInputs requirements;
    private final RepositoryInputs repositories;
    private final AuditWriter audit;
    private final JsonMapper json;
    private final TransactionTemplate readOnly;
    private final ActivityLog activity;
    private final BaAnalysisHandler.Describer describer = new BaAnalysisHandler.Describer();

    DesignGenerationHandler(ActivityLog activity, ModelGateway gateway, DesignRepository designs, ReviewRepository reviews,
                            AnalysisRepository analysis, ClarificationRepository clarifications,
                            RequirementInputs requirements, RepositoryInputs repositories, AuditWriter audit,
                            JsonMapper json, PlatformTransactionManager transactionManager) {
        this.activity = activity;
        this.gateway = gateway;
        this.designs = designs;
        this.reviews = reviews;
        this.analysis = analysis;
        this.clarifications = clarifications;
        this.requirements = requirements;
        this.repositories = repositories;
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
        UUID versionId = BaAnalysisHandler.uuid(job, "tddVersionId");
        Inputs inputs = readOnly.execute(s -> load(versionId));
        if (inputs == null) {
            return null; // no longer generating (superseded or already done): nothing to do
        }
        UUID requirementId = inputs.version().requirementId();
        ActivityLog.Stage stage = ActivityLog.Stage.DESIGN;
        boolean noCode = inputs.inventory().files().size() < 3;
        activity.step(requirementId, stage, versionId, "Started the technical design" + (job.attemptNo() > 1
                ? " (attempt " + job.attemptNo() + ")" : "") + ": reading the approved BRD ("
                + ActivityLog.count(inputs.brd().items().size(), "statement") + ")"
                + (noCode ? "; the repository has little or no code, so this is designed as a new product."
                        : " and the code (" + ActivityLog.count(inputs.inventory().files().size(), "file") + ")."));
        try {
            List<DataBlock> context = new ArrayList<>();
            context.add(ContextAssembler.requirement(inputs.requirement()));
            context.add(brdBlock(inputs.brd()));
            if (!inputs.answers().isEmpty()) {
                context.add(new DataBlock("answered clarifications (question and the client's answer)",
                        json.writeValueAsString(inputs.answers())));
            }
            if (inputs.version().changeRequest() != null) {
                context.add(new DataBlock("change request from the client's reviewers for this design version",
                        inputs.version().changeRequest()));
            }
            List<DataBlock> filesData = new ArrayList<>(context);
            filesData.addAll(ContextAssembler.inventory(inputs.inventory()));
            activity.step(requirementId, stage, versionId, "Deciding which code the design must build on.");
            ModelResponse<DesignContracts.Files> files = gateway.generate(new ModelRequest<>(BaPrompts.ARCH_FILES,
                    filesData, DesignContracts.Files.class, inputs.version().requirementId(), 2048, null));
            describer.repaired(activity, requirementId, stage, versionId, files);

            Set<String> relevant = relevantPaths(files.output().relevantPaths(), inputs.inventory());
            if (!relevant.isEmpty()) {
                activity.done(requirementId, stage, versionId, "Picked " + ActivityLog.count(relevant.size(), "file")
                        + " to read: " + ActivityLog.names(relevant, 4) + ".");
            }
            Map<String, String> code = repositories.readFiles(inputs.inputSet().sources(), relevant);
            List<DataBlock> designData = new ArrayList<>(context);
            designData.add(checklist(inputs.brd()));
            designData.add(repositorySummary(inputs.inventory()));
            designData.add(new DataBlock("approach note (model output, untrusted)", files.output().approach()));
            designData.addAll(ContextAssembler.fileExcerpts(code, relevant));
            designData.addAll(ContextAssembler.inventory(inputs.inventory()));
            activity.step(requirementId, stage, versionId, "Writing the design: architecture, components, APIs, data "
                    + "model, security, rollout and test plan. This takes from about a minute to several minutes for a large requirement.");
            ModelResponse<DesignContracts.Proposal> design = gateway.generate(new ModelRequest<>(BaPrompts.ARCH_DESIGN,
                    designData, DesignContracts.Proposal.class, inputs.version().requirementId(),
                    BaAnalysisHandler.LONG_OUTPUT_TOKENS, BaAnalysisHandler.LONG_CALL_TIMEOUT)
                    .withProgress(DraftWatcher.design(activity, requirementId, versionId, DesignService.SECTIONS, json)));
            describer.repaired(activity, requirementId, stage, versionId, design);

            Set<String> brdKeys = new LinkedHashSet<>();
            Map<String, String> brdLabels = new HashMap<>();
            Map<String, Integer> perSection = new HashMap<>();
            inputs.brd().items().forEach(i -> {
                brdKeys.add(i.itemKey());
                int n = perSection.merge(i.section(), 1, Integer::sum);
                brdLabels.put(i.itemKey(), "BRD " + sectionLabel(i.section()) + " " + n);
            });
            List<ItemRow> items = verify(design.output(), code, brdKeys, brdLabels);
            String questions = json.writeValueAsString(design.output().openQuestions().stream().map(String::strip).toList());
            String summary = readable(design.output().summary().strip(), brdLabels);
            long verified = items.stream().filter(i -> "VERIFIED".equals(i.supportState())).count();
            long flagged = items.stream().filter(i -> "FLAGGED".equals(i.supportState())).count();
            activity.done(requirementId, stage, versionId, "The model returned " + ActivityLog.count(items.size(), "design item")
                    + ". Checked the code references: " + verified + " based on verified code, " + flagged + " flagged, "
                    + (items.size() - verified - flagged) + " not traced to code.");
            return new Outcome(versionId, summary, questions, items, contentHash(summary, items, questions),
                    design.provider(), design.modelId());
        } catch (ModelException e) {
            activity.add(requirementId, stage, versionId, ActivityLog.Kind.FAIL, BaAnalysisHandler.describe(e));
            throw BaAnalysisHandler.failure(e);
        } catch (IOException e) {
            activity.fail(requirementId, stage, versionId, "The code could not be read; retrying automatically.");
            throw JobFailure.retryable("SNAPSHOT_READ_FAILED", e);
        }
    }

    private Inputs load(UUID versionId) {
        VersionRow version = designs.find(versionId, false).orElse(null);
        if (version == null || !"GENERATING".equals(version.status())) {
            return null;
        }
        VersionView brd = reviews.view(version.brdVersionId(), false);
        InputSetRow set = analysis.inputSet(designs.brdInputSet(version.brdVersionId())).orElseThrow();
        Content content = requirements.currentRevision(version.requirementId()).orElseThrow().content();
        Inventory inventory = repositories.inventory(set.sources(), ContextAssembler.MAX_FILES_LISTED,
                ContextAssembler.MAX_SYMBOLS);
        return new Inputs(version, brd, content, clarifications.answeredInputs(version.requirementId()), set, inventory);
    }

    /** The approved BRD, one line per statement with its item key (the keys the design must reference). */
    static DataBlock brdBlock(VersionView brd) {
        StringBuilder text = new StringBuilder();
        if (brd.understanding() != null) {
            text.append("Summary: ").append(brd.understanding()).append("\n\n");
        }
        for (ItemView item : brd.items()) {
            text.append('[').append(item.itemKey()).append("] ").append(item.section()).append(" / ")
                    .append(item.kind()).append(": ").append(item.statement());
            if (item.component() != null) {
                text.append(" (component: ").append(item.component()).append(')');
            }
            text.append('\n');
        }
        return new DataBlock("approved BRD version " + brd.versionNo() + " (item key, section / kind: statement)",
                text.toString());
    }

    /** The BRD statements the design must reference (the same set the coverage check uses). */
    static DataBlock checklist(VersionView brd) {
        StringBuilder text = new StringBuilder();
        for (ItemView item : brd.items()) {
            if (DesignService.COVERED_SECTIONS.contains(item.section())) {
                text.append(item.itemKey()).append(" (").append(item.section()).append("): ").append(item.statement())
                        .append('\n');
            }
        }
        return new DataBlock("checklist: BRD statements the design must address (each key must appear in brdRefs of "
                + "at least one item)", text.isEmpty() ? "(none)" : text.toString());
    }

    /** How much code there is, so the agent designs a new product when there is little or none. */
    static DataBlock repositorySummary(Inventory inventory) {
        long source = inventory.files().stream().filter(RepositoryInputs.FileInfo::indexable).count();
        long bytes = inventory.files().stream().mapToLong(RepositoryInputs.FileInfo::sizeBytes).sum();
        String verdict = source < 3 || bytes < 2_000
                ? "The repository contains little or no code: design this as a new product."
                : "The repository contains existing code: design the change within it.";
        return new DataBlock("repository summary (computed by the platform)", inventory.files().size() + " files, "
                + source + " source files, " + bytes + " bytes in total. " + verdict);
    }

    static Set<String> relevantPaths(List<String> proposed, Inventory inventory) {
        Set<String> known = new LinkedHashSet<>();
        inventory.files().forEach(f -> known.add(f.path()));
        Set<String> chosen = new LinkedHashSet<>();
        for (String path : proposed) {
            if (known.contains(path) && chosen.size() < MAX_FILES) {
                chosen.add(path);
            }
        }
        return chosen;
    }

    /**
     * Engine checks: repository citations verified against the snapshot text and against the item (the cited lines must
     * share a key term with the item's title or detail); BRD references limited to real item keys; stable item keys.
     * VERIFIED: at least one citation found and related to the item. FLAGGED: a quote that is not in the cited lines
     * (the model claimed code that is not there). NOT_TRACED: no citation, or only citations that exist but do not
     * relate to the item (they are dropped, so a file cited only because it exists never counts as evidence).
     */
    List<ItemRow> verify(DesignContracts.Proposal proposal, Map<String, String> code, Set<String> brdKeys) {
        return verify(proposal, code, brdKeys, Map.of());
    }

    /** "ACCEPTANCE_CRITERIA" reads as "acceptance criteria". */
    static String sectionLabel(String section) {
        return section == null ? "item" : section.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /**
     * BRD item keys never belong in the text (the model should use brdRefs): each one is replaced by a readable
     * reference such as "BRD acceptance criteria 2".
     */
    static String readable(String text, Map<String, String> brdLabels) {
        String out = text;
        for (Map.Entry<String, String> e : brdLabels.entrySet()) {
            if (out.contains(e.getKey())) {
                out = out.replace(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    List<ItemRow> verify(DesignContracts.Proposal proposal, Map<String, String> code, Set<String> brdKeys,
                         Map<String, String> brdLabels) {
        List<ItemRow> rows = new ArrayList<>();
        Map<String, Integer> used = new HashMap<>();
        int ordinal = 0;
        for (DesignContracts.Item item : DesignSplitter.split(proposal.items())) {
            List<Map<String, Object>> valid = new ArrayList<>();
            boolean fabricated = false;
            for (Evidence e : item.evidence()) {
                if (e.type() != EvidenceType.REPOSITORY) {
                    continue;
                }
                VerifiedEvidence v = EvidenceVerifier.checkRepository(e, item.title() + " " + item.detail(), code);
                if (v == null) {
                    fabricated = true;
                } else if (v.supportPassed()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", v.path());
                    m.put("startLine", v.startLine());
                    m.put("endLine", v.endLine());
                    m.put("rangeSha256", v.rangeSha256());
                    m.put("quote", v.quote());
                    valid.add(m);
                }
            }
            String state = !valid.isEmpty() ? "VERIFIED" : fabricated ? "FLAGGED" : "NOT_TRACED";
            List<String> refs = item.brdRefs().stream().map(String::strip).filter(brdKeys::contains).distinct().toList();
            String section = item.section().name();
            ordinal++;
            rows.add(new ItemRow(ordinal, key(section, item.title(), used), section, item.kind().name(),
                    readable(item.title().strip(), brdLabels), readable(item.detail().strip(), brdLabels),
                    blankToNull(item.component()),
                    json.writeValueAsString(refs), state, json.writeValueAsString(valid)));
        }
        return rows;
    }

    private static String key(String section, String title, Map<String, Integer> used) {
        String base = section.toLowerCase(Locale.ROOT).replace('_', '-') + "-"
                + InputSets.sha256(EvidenceVerifier.normalise(title).toLowerCase(Locale.ROOT)).substring(0, 12);
        int n = used.merge(base, 1, Integer::sum);
        return n == 1 ? base : base + "-" + n;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    String contentHash(String summary, List<ItemRow> items, String questions) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("summary", summary);
        canonical.put("items", items);
        canonical.put("openQuestions", questions);
        return InputSets.sha256(json.writeValueAsString(canonical));
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        if (outcome == null) {
            return;
        }
        VersionRow version = designs.find(outcome.versionId(), true).orElse(null);
        if (version == null || !"GENERATING".equals(version.status())) {
            return; // superseded or already stored: the result is discarded
        }
        for (ItemRow item : outcome.items()) {
            designs.insertItem(version.tenantId(), version.id(), item);
        }
        designs.complete(version.id(), outcome.summary(), outcome.openQuestionsJson(), outcome.contentHash(),
                outcome.provider(), outcome.modelId());
        // GD-27: the Technical Approvers review it; the author follows it.
        Set<UUID> recipients = new LinkedHashSet<>(designs.technicalApprovers(version.projectId()));
        recipients.add(designs.requirementOwner(version.requirementId()));
        for (UUID user : recipients) {
            reviews.notify(version.tenantId(), user, "TDD_READY_FOR_REVIEW", version.requirementId());
        }
        audit.record(AuditAction.TDD_VERSION_CREATED, "TDD_VERSION", version.id(), AuditResult.SUCCESS);
        activity.done(version.requirementId(), ActivityLog.Stage.DESIGN, version.id(), "Design version "
                + version.versionNo() + " ready for review: " + ActivityLog.count(outcome.items().size(), "item") + ".");
    }
}
