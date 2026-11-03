package io.github.tony079.sdlc.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * The only authority for requirement workflow state (PRD 3, TDD 9.5). No other module writes workflow tables.
 * Every method runs inside the caller's transaction, so a state change commits together with the domain change
 * and audit event that caused it. The database independently enforces that the projected state always matches
 * the append-only transition history and that only legal transitions are stored.
 */
public interface WorkflowEngine {

    /** Creates the workflow instance for a new requirement in DRAFT. */
    void start(UUID requirementId);

    WorkflowState state(UUID requirementId);

    /**
     * Applies a transition if it is legal from the current state. An illegal request is recorded as
     * ILLEGAL_TRANSITION_ATTEMPTED in its own transaction (so the record survives the caller's rollback) and
     * then rejected with 409 ILLEGAL_TRANSITION. Nothing changes.
     *
     * @param reason free-text reason where the transition requires one (T29), otherwise null
     */
    WorkflowState transition(UUID requirementId, Transition transition, String reason);

    List<TimelineEntry> timeline(UUID requirementId);
}
