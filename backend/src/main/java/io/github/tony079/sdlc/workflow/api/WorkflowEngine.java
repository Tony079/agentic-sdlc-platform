package io.github.tony079.sdlc.workflow.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only authority for requirement workflow state (PRD 3, TDD 9.5). No other module writes workflow tables.
 * Every method runs inside the caller's transaction, so a state change commits together with the domain change,
 * the audit event and the outbox event it causes. The database independently enforces that the projected state
 * always matches the append-only transition history and that only legal transitions are stored.
 */
public interface WorkflowEngine {

    /** Creates the workflow instance for a new requirement in DRAFT. */
    void start(UUID requirementId);

    WorkflowState state(UUID requirementId);

    /**
     * The current state with the workflow instance locked until the caller's transaction ends, so no transition
     * (for example routing by the worker) can happen in between. For changes that depend on the state.
     */
    WorkflowState lockState(UUID requirementId);

    /**
     * T01: DRAFT to SUBMITTED. Publishes RequirementSubmitted (with the route) in the same transaction; routing
     * then happens asynchronously in the worker through the transactional outbox.
     */
    WorkflowState submit(UUID requirementId, Route route);

    /**
     * Applies a user transition (currently T29) if it is legal from the current state. An illegal request is
     * recorded as ILLEGAL_TRANSITION_ATTEMPTED in its own transaction (so the record survives the caller's
     * rollback) and then rejected with 409 ILLEGAL_TRANSITION. Nothing changes.
     *
     * @param reason free-text reason where the transition requires one (T29), otherwise null
     */
    WorkflowState transition(UUID requirementId, Transition transition, String reason);

    List<TimelineEntry> timeline(UUID requirementId);

    /**
     * Engine only (worker, no user): the repository is validated and pinned. T07 when {@code documentDecisionNeeded},
     * otherwise T06 (Q-B Option A: never T08, never ANALYSING, no BA job). Marks the validation stage SUCCEEDED.
     * Returns empty, changing nothing, if the requirement is no longer REPO_VALIDATING (for example cancelled).
     */
    Optional<WorkflowState> completeRepositoryValidation(UUID requirementId, UUID stageExecutionId,
                                                         boolean documentDecisionNeeded);

    /** Engine only: T09 after the validation job is dead. Empty if the requirement is no longer REPO_VALIDATING. */
    Optional<WorkflowState> failRepositoryValidation(UUID requirementId);

    /**
     * Engine only, in the caller's transaction after {@link #lockState}: enters analysis with a frozen input set (T11 from
     * AWAITING_DOCUMENTS, T15 from ANALYSING). Creates the BA_ANALYSIS stage execution with the input hash, enqueues the
     * BA_ANALYSIS job (payload plus the stage id) and publishes AnalysisStarted. The caller has evaluated guard 9.4.
     * Returns the stage execution id, or empty if the state allows neither transition.
     */
    Optional<UUID> startAnalysis(UUID requirementId, String inputHash, Map<String, Object> jobPayload);

    /**
     * Engine only: whether {@code stageExecutionId} is the latest BA_ANALYSIS execution of the requirement and is still
     * live (not STALE, CANCELLED, FAILED or SUPERSEDED). Used by the commit step (Tx C).
     */
    boolean isCurrentAnalysis(UUID requirementId, UUID stageExecutionId);

    /** Engine only, Tx C: T18 then T20; the stage SUCCEEDED; BrdVersionCreated and ReviewQueued. */
    Optional<WorkflowState> completeAnalysis(UUID requirementId, UUID stageExecutionId, UUID brdVersionId);

    /** Engine only: the execution's inputs changed (TDD 9.6): stage STALE, ExecutionStale, T14 unless restarted after. */
    void markAnalysisStale(UUID requirementId, UUID stageExecutionId, boolean waitForInputs);

    /** Engine only: T19 after the BA job is dead or fatal. Empty if the requirement is no longer ANALYSING. */
    Optional<WorkflowState> failAnalysis(UUID requirementId);
}
