package io.github.tony079.sdlc.businessanalysis.internal;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.AnalysisRepository.InputSetRow;
import io.github.tony079.sdlc.businessanalysis.internal.AnalysisRepository.PendingRow;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Evidence;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.EvidenceType;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Item;
import io.github.tony079.sdlc.businessanalysis.internal.BaContracts.Proposal;
import io.github.tony079.sdlc.businessanalysis.internal.EvidenceVerifier.DocumentSource;
import io.github.tony079.sdlc.businessanalysis.internal.EvidenceVerifier.VerifiedItem;
import io.github.tony079.sdlc.document.api.DocumentProcessing;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;
import jakarta.validation.Validator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BA_COMMIT_RESULT (TDD 12.6, Tx C): the trusted engine side. Execute: reads the pending proposal and the cited
 * snapshot files and document chunks, and verifies every citation (16.3). Commit, in one fenced transaction: the
 * pending result is still PENDING; the workflow is ANALYSING and this is the current execution; the input set hash
 * recomputed from the current data equals the frozen one (a document change during analysis fails here, WF-S02);
 * the proposal re-validates. Then the BRD version, items and verified evidence are written and T18/T20 applied.
 * Otherwise the result is rejected (REJECTED_STALE / REJECTED_INVALID) and no BRD is created.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class BaCommitHandler implements JobHandler<BaCommitHandler.Outcome> {

    static final String JOB_TYPE = "BA_COMMIT_RESULT";

    /** items null: nothing to do (already decided) or invalid (invalidCode set). */
    record Outcome(UUID pendingId, List<VerifiedItem> items, String invalidCode) {
    }

    private final AnalysisRepository repository;
    private final RepositoryInputs repositories;
    private final DocumentProcessing documents;
    private final InputSets inputSets;
    private final AnalysisStarter starter;
    private final WorkflowEngine workflow;
    private final AuditWriter audit;
    private final Validator validator;
    private final JsonMapper json;
    private final JsonMapper strict;
    private final TransactionTemplate readOnly;

    BaCommitHandler(AnalysisRepository repository, RepositoryInputs repositories, DocumentProcessing documents,
                    InputSets inputSets, AnalysisStarter starter, WorkflowEngine workflow, AuditWriter audit,
                    Validator validator, JsonMapper json, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.repositories = repositories;
        this.documents = documents;
        this.inputSets = inputSets;
        this.starter = starter;
        this.workflow = workflow;
        this.audit = audit;
        this.validator = validator;
        this.json = json;
        this.strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    private record Loaded(PendingRow pending, InputSetRow inputSet, Map<UUID, DocumentSource> documents,
                          Set<String> snapshotPaths) {
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID pendingId = BaAnalysisHandler.uuid(job, "pendingResultId");
        Loaded loaded = readOnly.execute(status -> load(pendingId));
        if (loaded == null) {
            throw JobFailure.fatal("PENDING_RESULT_NOT_FOUND", null);
        }
        if (!"PENDING".equals(loaded.pending().status())) {
            return new Outcome(pendingId, null, null);
        }
        Proposal proposal;
        try {
            proposal = strict.readValue(loaded.pending().proposalJson(), Proposal.class);
        } catch (RuntimeException e) {
            return new Outcome(pendingId, null, "PROPOSAL_UNREADABLE");
        }
        if (!validator.validate(proposal).isEmpty()) {
            return new Outcome(pendingId, null, "PROPOSAL_INVALID");
        }
        Set<String> cited = new HashSet<>();
        for (Item item : proposal.items()) {
            for (Evidence evidence : item.evidence()) {
                if (evidence.type() == EvidenceType.REPOSITORY && evidence.path() != null
                        && loaded.snapshotPaths().contains(evidence.path())) {
                    cited.add(evidence.path());
                }
            }
        }
        Map<String, String> files;
        try {
            files = repositories.readFiles(loaded.inputSet().snapshotId(), cited);
        } catch (IOException e) {
            throw JobFailure.retryable("SNAPSHOT_READ_FAILED", e);
        }
        return new Outcome(pendingId, EvidenceVerifier.verify(proposal, files, loaded.documents()), null);
    }

    private Loaded load(UUID pendingId) {
        PendingRow pending = repository.pending(pendingId, false).orElse(null);
        if (pending == null) {
            return null;
        }
        InputSetRow set = repository.inputSet(pending.inputSetId()).orElseThrow();
        Map<UUID, DocumentSource> sources = new HashMap<>();
        for (JsonNode doc : json.readTree(set.documentsJson())) {
            if (!doc.path("included").asBoolean(false)) {
                continue;
            }
            UUID versionId = UUID.fromString(doc.path("versionId").asString());
            int extraction = doc.path("extractionNo").asInt();
            Map<Integer, EvidenceVerifier.Chunk> chunks = new HashMap<>();
            for (DocumentProcessing.Chunk chunk : documents.chunks(versionId, extraction)) {
                chunks.put(chunk.ordinal(), new EvidenceVerifier.Chunk(chunk.ordinal(), chunk.label(), chunk.text()));
            }
            sources.put(versionId, new DocumentSource(versionId, extraction, chunks));
        }
        Set<String> paths = new HashSet<>();
        repositories.inventory(set.snapshotId(), 100_000, 0).files().forEach(f -> paths.add(f.path()));
        return new Loaded(pending, set, sources, paths);
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        PendingRow pending = repository.pending(outcome.pendingId(), true).orElseThrow();
        if (!"PENDING".equals(pending.status())) {
            return;
        }
        UUID requirementId = pending.requirementId();
        WorkflowState state = workflow.lockState(requirementId);
        if (state != WorkflowState.ANALYSING || !workflow.isCurrentAnalysis(requirementId, pending.stageExecutionId())) {
            reject(pending, "REJECTED_STALE", "STAGE_NOT_CURRENT");
            return;
        }
        InputSetRow set = repository.inputSet(pending.inputSetId()).orElseThrow();
        String current = inputSets.evaluate(requirementId, set.correctionsJson()).map(InputSets.Draft::inputHash)
                .orElse("NOT_READY");
        if (!current.equals(pending.inputHash())) {
            reject(pending, "REJECTED_STALE", "INPUTS_CHANGED");
            boolean guardHolds = !"NOT_READY".equals(current);
            workflow.markAnalysisStale(requirementId, pending.stageExecutionId(), !guardHolds);
            if (guardHolds) {
                starter.start(requirementId, set.correctionsJson()); // T15: new execution with the new input set
            }
            return;
        }
        if (outcome.items() == null) {
            reject(pending, "REJECTED_INVALID", outcome.invalidCode() == null ? "PROPOSAL_INVALID" : outcome.invalidCode());
            workflow.failAnalysis(requirementId);
            return;
        }
        UUID brd = repository.brdFor(job.tenantId(), requirementId);
        repository.invalidateApprovalsOfOpenVersions(requirementId);
        repository.supersedeOpenVersions(requirementId);
        String excluded = excludedDocuments(set.documentsJson());
        UUID version = repository.insertVersion(job.tenantId(), brd, requirementId, pending,
                contentHash(pending.understanding(), outcome.items(), excluded), excluded);
        repository.insertItems(job.tenantId(), version, set.repositoryId(), set.commitSha(), outcome.items());
        repository.decidePending(pending.id(), "COMMITTED", null);
        if (outcome.items().stream().anyMatch(i -> i.rejectedCitations() > 0)) {
            audit.record(AuditAction.CITATION_REJECTED, "BRD_VERSION", version, AuditResult.DENIED);
        }
        workflow.completeAnalysis(requirementId, pending.stageExecutionId(), version);
    }

    private void reject(PendingRow pending, String status, String reason) {
        repository.decidePending(pending.id(), status, reason);
        audit.record(AuditAction.BA_RESULT_REJECTED, "BA_RESULT", pending.id(), AuditResult.DENIED);
    }

    /** Documents of the input set that were not analysed, with their state (trusted list, AC-DOC-12). */
    private String excludedDocuments(String documentsJson) {
        List<Map<String, String>> excluded = new java.util.ArrayList<>();
        for (JsonNode doc : json.readTree(documentsJson)) {
            if (!doc.path("included").asBoolean(false)) {
                excluded.add(Map.of("documentId", doc.path("documentId").asString(), "fileName",
                        doc.path("fileName").asString(), "state", doc.path("state").asString()));
            }
        }
        return json.writeValueAsString(excluded);
    }

    /** SHA-256 over a canonical serialisation of the version's content (TDD 16.1, approval binding 19.2). */
    String contentHash(String understanding, List<VerifiedItem> items, String excluded) {
        Map<String, Object> canonical = new java.util.LinkedHashMap<>();
        canonical.put("understanding", understanding);
        canonical.put("items", items);
        canonical.put("excludedDocuments", excluded);
        return InputSets.sha256(json.writeValueAsString(canonical));
    }
}
