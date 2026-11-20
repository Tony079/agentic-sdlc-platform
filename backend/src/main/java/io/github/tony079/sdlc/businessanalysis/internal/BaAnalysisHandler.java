package io.github.tony079.sdlc.businessanalysis.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.AnalysisRepository.InputSetRow;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.PhaseA;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Proposal;
import io.github.tony079.sdlc.businessanalysis.internal.ContextAssembler.DocumentChunk;
import io.github.tony079.sdlc.document.api.DocumentProcessing;
import io.github.tony079.sdlc.modelgateway.api.DataBlock;
import io.github.tony079.sdlc.modelgateway.api.ModelException;
import io.github.tony079.sdlc.modelgateway.api.ModelGateway;
import io.github.tony079.sdlc.modelgateway.api.ModelRequest;
import io.github.tony079.sdlc.modelgateway.api.ModelResponse;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;
import io.github.tony079.sdlc.requirement.api.RequirementInputs;
import io.github.tony079.sdlc.requirement.api.RequirementView.Content;
import io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs;
import io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs.Inventory;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BA_ANALYSIS (TDD 12.3, 12.4): the BA agent. Execute (outside any transaction, under the lease): reads the frozen
 * input set, runs Phase A (inventory and relevance) and Phase B (BRD proposal) through the provider-neutral model
 * gateway with fixed versioned instructions and untrusted data blocks; the output is schema-validated by the gateway.
 * Commit (Tx P): stores the proposal as a pending result (never approvable) and enqueues BA_COMMIT_RESULT.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class BaAnalysisHandler implements JobHandler<BaAnalysisHandler.Outcome> {

    static final String JOB_TYPE = "BA_ANALYSIS";
    static final Duration MODEL_RETRY_DELAY = Duration.ofMinutes(2);
    /**
     * The BRD and the design are long answers: a large model can stream for several minutes. The cap stays within
     * the job's runtime limit (30 minutes); output tokens are further capped by the registered model's maximum.
     */
    static final Duration LONG_CALL_TIMEOUT = Duration.ofMinutes(10);
    static final int LONG_OUTPUT_TOKENS = 32768;
    static final Duration NOT_CONFIGURED_DELAY = Duration.ofMinutes(15);

    record Outcome(String proposalJson, String understanding, String provider, String modelId) {
    }

    private final ModelGateway gateway;
    private final AnalysisRepository repository;
    private final RequirementInputs requirements;
    private final RepositoryInputs repositories;
    private final DocumentProcessing documents;
    private final WorkflowEngine workflow;
    private final JobQueue jobs;
    private final AuditWriter audit;
    private final JsonMapper json;
    private final TransactionTemplate readOnly;
    private final ActivityLog activity;
    private final ClarificationRepository clarifications;

    BaAnalysisHandler(ActivityLog activity, ModelGateway gateway, AnalysisRepository repository, RequirementInputs requirements,
                      RepositoryInputs repositories, DocumentProcessing documents, WorkflowEngine workflow, JobQueue jobs,
                      AuditWriter audit, JsonMapper json, PlatformTransactionManager transactionManager,
                      ClarificationRepository clarifications) {
        this.activity = activity;
        this.clarifications = clarifications;
        this.gateway = gateway;
        this.repository = repository;
        this.requirements = requirements;
        this.repositories = repositories;
        this.documents = documents;
        this.workflow = workflow;
        this.jobs = jobs;
        this.audit = audit;
        this.json = json;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    private record Inputs(InputSetRow inputSet, Content content, Inventory inventory, List<DocumentChunk> chunks,
                          List<java.util.Map<String, String>> settled) {
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID inputSetId = uuid(job, "inputSetId");
        Inputs inputs = readOnly.execute(status -> load(inputSetId));
        if (inputs == null) {
            throw JobFailure.fatal("INPUT_SET_NOT_FOUND", null);
        }
        UUID requirementId = job.aggregateId();
        UUID run = job.stageExecutionId();
        // GD-28: a new product has no code, so Phase A (choosing files) is skipped.
        boolean newProduct = "NEW_PRODUCT".equals(inputs.content().kind());
        long documentCount = inputs.chunks().stream().map(DocumentChunk::versionId).distinct().count();
        String attempt = job.attemptNo() > 1 ? " (attempt " + job.attemptNo() + ")" : "";
        activity.step(requirementId, ActivityLog.Stage.BRD, run, "Started the analysis" + attempt + ": reading the "
                + "requirement and " + ActivityLog.count((int) documentCount, "document")
                + (newProduct ? "; this is a new product, so there is no code to read."
                        : " and the code (" + ActivityLog.count(inputs.inventory().files().size(), "file") + ")."));
        // Uploaded documents that could not be used (unreadable, image-only, rejected) are named, so a reader knows
        // the BRD was written without them.
        List<String> unused = new ArrayList<>();
        for (JsonNode doc : json.readTree(inputs.inputSet().documentsJson())) {
            if (!doc.path("included").asBoolean(false)) {
                unused.add(doc.path("fileName").asString("a document") + " (" + documentState(doc.path("state").asString(""))
                        + ")");
            } else if (inputs.chunks().stream().noneMatch(c -> c.versionId().equals(doc.path("versionId").asString()))) {
                unused.add(doc.path("fileName").asString("a document") + " (no readable text)");
            }
        }
        if (!unused.isEmpty()) {
            activity.warn(requirementId, ActivityLog.Stage.BRD, run, "Could not use " + ActivityLog.count(unused.size(),
                    "uploaded document") + ": " + ActivityLog.names(unused, 5) + ". The BRD is written without "
                    + (unused.size() == 1 ? "it" : "them") + "; upload a text-based version to include "
                    + (unused.size() == 1 ? "it" : "them") + ".");
        }
        try {
            PhaseA phaseAOutput;
            if (newProduct) {
                phaseAOutput = new PhaseA("New product: " + inputs.content().title(), List.of(), List.of());
            } else {
                activity.step(requirementId, ActivityLog.Stage.BRD, run, "Choosing which code files matter for this requirement.");
                List<DataBlock> phaseAData = new ArrayList<>();
                phaseAData.add(ContextAssembler.requirement(inputs.content()));
                phaseAData.addAll(ContextAssembler.inventory(inputs.inventory()));
                phaseAData.addAll(ContextAssembler.documents(inputs.chunks()));
                ModelResponse<PhaseA> phaseA = gateway.generate(new ModelRequest<>(BaPrompts.PHASE_A, phaseAData,
                        PhaseA.class, job.aggregateId(), 2048, null));
                repaired(requirementId, ActivityLog.Stage.BRD, run, phaseA);
                phaseAOutput = phaseA.output();
            }

            Set<String> relevant = newProduct ? Set.of() : ContextAssembler.relevantPaths(phaseAOutput, inputs.inventory());
            if (!newProduct) {
                activity.done(requirementId, ActivityLog.Stage.BRD, run, "Picked " + ActivityLog.count(relevant.size(), "file")
                        + " to read: " + ActivityLog.names(relevant, 4) + ".");
            }
            Map<String, String> files = newProduct ? Map.of() : repositories.readFiles(inputs.inputSet().sources(), relevant);
            List<DataBlock> phaseBData = new ArrayList<>();
            phaseBData.add(ContextAssembler.requirement(inputs.content()));
            phaseBData.add(new DataBlock("product type (computed by the platform)", newProduct
                    ? "NEW_PRODUCT: there is no existing code; the requirement describes a product to be built."
                    : "EXISTING_PRODUCT: the requirement changes the code shown in the repository excerpts."));
            if (!newProduct) {
                phaseBData.add(ContextAssembler.phaseASummary(phaseAOutput));
            }
            phaseBData.addAll(ContextAssembler.fileExcerpts(files, relevant));
            phaseBData.addAll(ContextAssembler.inventory(inputs.inventory()));
            phaseBData.addAll(ContextAssembler.documents(inputs.chunks()));
            // The client's answers to earlier clarifications are settled decisions, given in their own block so the
            // model applies them instead of asking again. Every question settled for this requirement is included,
            // not only those carried by this input set (a later revision or re-analysis starts a new one).
            String corrections = inputs.inputSet().correctionsJson();
            java.util.Map<String, JsonNode> answers = new java.util.LinkedHashMap<>();
            List<JsonNode> notes = new ArrayList<>();
            if (corrections != null && !"[]".equals(corrections.replace(" ", ""))) {
                for (JsonNode node : json.readTree(corrections)) {
                    if ("CLARIFICATION".equals(node.path("source").asString(""))) {
                        answers.put(node.path("clarificationId").asString(String.valueOf(answers.size())), node);
                    } else {
                        notes.add(node);
                    }
                }
            }
            for (java.util.Map<String, String> settled : inputs.settled()) {
                answers.putIfAbsent(settled.get("clarificationId"), json.valueToTree(settled));
            }
            if (!answers.isEmpty()) {
                phaseBData.add(new DataBlock("answered clarifications (question and the client's answer; settled"
                        + " decisions: apply them and do not ask these questions again, even reworded)",
                        json.writeValueAsString(answers.values())));
            }
            if (!notes.isEmpty()) {
                phaseBData.add(new DataBlock("reviewer correction notes (item key, instruction)",
                        json.writeValueAsString(notes)));
            }
            activity.step(requirementId, ActivityLog.Stage.BRD, run, "Writing the BRD: context, impact, scenarios, risks, "
                    + "acceptance criteria and a feasibility verdict. This takes from about a minute to several minutes for a large requirement.");
            ModelResponse<Proposal> phaseB = gateway.generate(new ModelRequest<>(BaPrompts.PHASE_B, phaseBData,
                    Proposal.class, job.aggregateId(), LONG_OUTPUT_TOKENS, LONG_CALL_TIMEOUT)
                    .withProgress(DraftWatcher.brd(activity, requirementId, run, json)));
            repaired(requirementId, ActivityLog.Stage.BRD, run, phaseB);
            activity.done(requirementId, ActivityLog.Stage.BRD, run, "The model returned "
                    + ActivityLog.count(phaseB.output().items().size(), "statement") + " and "
                    + ActivityLog.count(phaseB.output().clarifications().size(), "question")
                    + ". Checking every code and document reference next.");
            return new Outcome(json.writeValueAsString(phaseB.output()), phaseAOutput.understanding(),
                    phaseB.provider(), phaseB.modelId());
        } catch (ModelException e) {
            activity.add(requirementId, ActivityLog.Stage.BRD, run, ActivityLog.Kind.FAIL, describe(e));
            throw failure(e);
        } catch (IOException e) {
            activity.fail(requirementId, ActivityLog.Stage.BRD, run, "The code could not be read; retrying automatically.");
            throw JobFailure.retryable("SNAPSHOT_READ_FAILED", e);
        }
    }

    void repaired(UUID requirementId, ActivityLog.Stage stage, UUID run, ModelResponse<?> response) {
        new Describer().repaired(activity, requirementId, stage, run, response);
    }

    /** GD-29: a first answer that was rejected and repaired is shown with the rule it broke (never the model text). */
    static final class Describer {
        void repaired(ActivityLog activity, UUID requirementId, ActivityLog.Stage stage, UUID run,
                      ModelResponse<?> response) {
            if (response.repairedBecause() != null) {
                activity.warn(requirementId, stage, run, "The model's first answer broke a rule ("
                        + response.repairedBecause() + "). Garuda asked it to correct the answer, and the corrected "
                        + "answer passed.");
            }
        }
    }

    /** What went wrong with a model call, for the timeline. Retryable failures are retried by the job queue. */
    static String describe(ModelException e) {
        return switch (e.code()) {
            case INVALID_OUTPUT -> "The model's answer broke a rule twice"
                    + (e.detail() == null ? "" : " (" + e.detail() + ")") + ". Garuda will try again automatically.";
            case EMPTY_OUTPUT -> "The model returned an empty answer twice. Garuda will try again automatically.";
            case BUDGET_EXCEEDED -> "The AI spending limit was reached, so the analysis stopped.";
            case CONFIGURATION_ERROR -> "The AI model is not configured or not registered (check the model and its prices in "
                    + ".env). The analysis waits until it is.";
            case RATE_LIMITED -> "The AI provider asked Garuda to slow down. Waiting a couple of minutes, then retrying.";
            case TIMEOUT, PROVIDER_UNAVAILABLE -> "The AI model did not respond in time. Retrying in a couple of minutes.";
            case PROVIDER_ERROR -> "The AI provider returned an error. Garuda will try again automatically.";
        };
    }

    /** Plain words for a document state the analysis could not use. */
    static String documentState(String state) {
        return switch (state) {
            case "UNSUPPORTED_CONTENT" -> "no text could be extracted, for example a scanned image";
            case "EXTRACTION_FAILED_FINAL" -> "text extraction failed";
            case "REJECTED_MALWARE" -> "rejected by the malware scan";
            case "REJECTED_INVALID" -> "not a supported or valid file";
            case "UPLOAD_FAILED" -> "the upload did not complete";
            default -> state.isEmpty() ? "not processed" : state.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        };
    }

    /** TDD 12.8 and 13.5: cost cap is fatal (COST_CAP); transient errors defer; no provider configured waits. */
    static JobFailure failure(ModelException e) {
        return switch (e.code()) {
            case BUDGET_EXCEEDED -> JobFailure.fatal("COST_CAP", null);
            case CONFIGURATION_ERROR -> JobFailure.dependencyUnavailable("MODEL_NOT_CONFIGURED", NOT_CONFIGURED_DELAY);
            case RATE_LIMITED -> JobFailure.dependencyUnavailable("MODEL_RATE_LIMITED", MODEL_RETRY_DELAY);
            case TIMEOUT, PROVIDER_UNAVAILABLE -> JobFailure.dependencyUnavailable("MODEL_UNAVAILABLE", MODEL_RETRY_DELAY);
            case INVALID_OUTPUT, EMPTY_OUTPUT, PROVIDER_ERROR -> JobFailure.retryable("MODEL_" + e.code().name(), null);
        };
    }

    private Inputs load(UUID inputSetId) {
        InputSetRow set = repository.inputSet(inputSetId).orElse(null);
        if (set == null) {
            return null;
        }
        Content content = requirements.currentRevision(set.requirementId()).orElseThrow().content();
        Inventory inventory = repositories.inventory(set.sources(), ContextAssembler.MAX_FILES_LISTED,
                ContextAssembler.MAX_SYMBOLS);
        List<DocumentChunk> chunks = new ArrayList<>();
        for (JsonNode doc : json.readTree(set.documentsJson())) {
            if (!doc.path("included").asBoolean(false)) {
                continue;
            }
            UUID versionId = UUID.fromString(doc.path("versionId").asString());
            for (DocumentProcessing.Chunk chunk : documents.chunks(versionId, doc.path("extractionNo").asInt())) {
                chunks.add(new DocumentChunk(versionId.toString(), chunk.ordinal(), doc.path("fileName").asString(),
                        chunk.label(), chunk.text()));
            }
        }
        return new Inputs(set, content, inventory, chunks, clarifications.answeredInputs(set.requirementId()));
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        UUID requirementId = job.aggregateId();
        UUID stage = job.stageExecutionId();
        if (!workflow.isCurrentAnalysis(requirementId, stage)) {
            return; // stale or cancelled execution: its result is discarded, never committed
        }
        InputSetRow set = repository.inputSet(uuidOrNull(job, "inputSetId")).orElseThrow();
        UUID pending = repository.insertPending(job.tenantId(), requirementId, stage, set.id(), set.inputHash(),
                job.leaseEpoch(), outcome.proposalJson(), outcome.understanding(), outcome.provider(), outcome.modelId());
        audit.record(AuditAction.AGENT_EXECUTION_FINISHED, "STAGE_EXECUTION", stage, AuditResult.SUCCESS);
        jobs.enqueue(new JobRequest(BaCommitHandler.JOB_TYPE, "ba-commit:" + pending, "REQUIREMENT", requirementId,
                stage, Map.of("pendingResultId", pending.toString()), 100));
    }

    static UUID uuid(ClaimedJob job, String attribute) throws JobFailure {
        UUID value = uuidOrNull(job, attribute);
        if (value == null) {
            throw JobFailure.fatal("PAYLOAD_INVALID", null);
        }
        return value;
    }

    private static UUID uuidOrNull(ClaimedJob job, String attribute) {
        try {
            return UUID.fromString(String.valueOf(job.payload().get(attribute)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
