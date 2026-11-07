package io.github.tony079.sdlc.workflow.internal;

import java.util.List;
import java.util.Optional;

import io.github.tony079.sdlc.workflow.api.Transition;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

/**
 * The legal transitions enabled so far (subset of TDD 9.3). Deterministic: the target depends only on the
 * transition and the current state. Mirrors the CHECK constraints on app.workflow_transition and the stored
 * definition R1-v1 (verified at startup by {@link DefinitionVerifier}); the database is the backstop.
 */
final class TransitionTable {

    record Rule(Transition transition, WorkflowState from, WorkflowState to) {
    }

    static final List<Rule> RULES = List.of(
            new Rule(Transition.T01_SUBMIT, WorkflowState.DRAFT, WorkflowState.SUBMITTED),
            new Rule(Transition.T02_ROUTE_REPOSITORY, WorkflowState.SUBMITTED, WorkflowState.REPO_VALIDATING),
            new Rule(Transition.T03_ROUTE_MANUAL_INTAKE, WorkflowState.SUBMITTED, WorkflowState.MANUAL_INTAKE),
            // R1-M4, Q-B Option A: T06/T07 only; no T08 (ANALYSING is R1-M5). T09 has no way back (O-27, no T30).
            new Rule(Transition.T06_REPO_VALIDATED, WorkflowState.REPO_VALIDATING, WorkflowState.AWAITING_DOCUMENTS),
            new Rule(Transition.T07_REPO_VALIDATED_DOCUMENT_DECISION, WorkflowState.REPO_VALIDATING,
                    WorkflowState.AWAITING_DOCUMENT_DECISION),
            new Rule(Transition.T09_REPO_VALIDATION_FAILED, WorkflowState.REPO_VALIDATING, WorkflowState.FAILED),
            // PRD section 6: CANCELLED is reachable from any non-terminal state; these are the states reachable so far.
            new Rule(Transition.T29_CANCEL, WorkflowState.DRAFT, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.SUBMITTED, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.MANUAL_INTAKE, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.REPO_VALIDATING, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.AWAITING_DOCUMENTS, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.AWAITING_DOCUMENT_DECISION, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.FAILED, WorkflowState.CANCELLED));

    private TransitionTable() {
    }

    /** The target state, or empty if the transition is not legal from {@code from}. */
    static Optional<WorkflowState> target(Transition transition, WorkflowState from) {
        return RULES.stream().filter(r -> r.transition() == transition && r.from() == from).map(Rule::to).findFirst();
    }
}
