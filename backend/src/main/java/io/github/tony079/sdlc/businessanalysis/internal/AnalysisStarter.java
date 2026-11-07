package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enters analysis when guard 9.4 holds (T11 from AWAITING_DOCUMENTS, T15 from ANALYSING after stale inputs): locks the
 * workflow instance first, evaluates the guard in the same transaction, freezes the input set, then lets the engine
 * create the stage execution and the BA_ANALYSIS job.
 */
@Component
@org.springframework.context.annotation.Profile("!migrate & !provision")
@Transactional(propagation = Propagation.MANDATORY)
class AnalysisStarter {

    private final WorkflowEngine workflow;
    private final InputSets inputSets;
    private final AnalysisRepository repository;
    private final JsonMapper json;

    AnalysisStarter(WorkflowEngine workflow, InputSets inputSets, AnalysisRepository repository, JsonMapper json) {
        this.workflow = workflow;
        this.inputSets = inputSets;
        this.repository = repository;
        this.json = json;
    }

    /** From AWAITING_DOCUMENTS only (event-driven); returns the new stage execution, if started. */
    Optional<UUID> tryStart(UUID requirementId) {
        if (workflow.lockState(requirementId) != WorkflowState.AWAITING_DOCUMENTS) {
            return Optional.empty();
        }
        return start(requirementId, "[]");
    }

    /** Requires the instance locked by the caller and a state that allows T11/T15. */
    Optional<UUID> start(UUID requirementId, String correctionsJson) {
        Optional<InputSets.Draft> draft = inputSets.evaluate(requirementId, correctionsJson);
        if (draft.isEmpty()) {
            return Optional.empty();
        }
        UUID inputSetId = repository.insertInputSet(TenantContext.requireTenant(), draft.get(),
                json.writeValueAsString(draft.get().documents()));
        return workflow.startAnalysis(requirementId, draft.get().inputHash(),
                Map.of("inputSetId", inputSetId.toString()));
    }
}
