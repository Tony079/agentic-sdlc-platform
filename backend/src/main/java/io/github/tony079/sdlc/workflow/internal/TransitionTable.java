package io.github.tony079.sdlc.workflow.internal;

import java.util.Optional;

import io.github.tony079.sdlc.workflow.api.Transition;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

/**
 * The legal transitions enabled in Milestone 2 (subset of TDD 9.3). Deterministic: the target depends only on the
 * transition and the current state. Mirrors the CHECK constraint on app.workflow_transition, which is the
 * database backstop if this table and the schema ever disagree.
 */
final class TransitionTable {

    private TransitionTable() {
    }

    /** The target state, or empty if the transition is not legal from {@code from}. */
    static Optional<WorkflowState> target(Transition transition, WorkflowState from) {
        return switch (transition) {
            case T01_SUBMIT -> from == WorkflowState.DRAFT ? Optional.of(WorkflowState.SUBMITTED) : Optional.empty();
            // PRD section 6: CANCELLED is reachable from any non-terminal state. Only DRAFT and SUBMITTED exist so far.
            case T29_CANCEL -> from == WorkflowState.DRAFT || from == WorkflowState.SUBMITTED
                    ? Optional.of(WorkflowState.CANCELLED) : Optional.empty();
        };
    }
}
