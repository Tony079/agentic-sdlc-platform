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
            // R1-M5 (TDD 9.3, 12.6).
            new Rule(Transition.T11_START_ANALYSIS, WorkflowState.AWAITING_DOCUMENTS, WorkflowState.ANALYSING),
            new Rule(Transition.T10_DOCUMENT_DECISION_NEEDED, WorkflowState.AWAITING_DOCUMENTS,
                    WorkflowState.AWAITING_DOCUMENT_DECISION),
            new Rule(Transition.T12_DOCUMENT_DECISION_RECORDED, WorkflowState.AWAITING_DOCUMENT_DECISION,
                    WorkflowState.AWAITING_DOCUMENTS),
            new Rule(Transition.T13_START_ANALYSIS_AFTER_DECISION, WorkflowState.AWAITING_DOCUMENT_DECISION,
                    WorkflowState.ANALYSING),
            new Rule(Transition.T16_AWAIT_CLARIFICATION, WorkflowState.ANALYSING, WorkflowState.AWAITING_CLARIFICATION),
            new Rule(Transition.T17_RESUME_ANALYSIS, WorkflowState.AWAITING_CLARIFICATION, WorkflowState.ANALYSING),
            new Rule(Transition.T14_ANALYSIS_STALE_WAIT, WorkflowState.ANALYSING, WorkflowState.AWAITING_DOCUMENTS),
            new Rule(Transition.T15_ANALYSIS_RESTART, WorkflowState.ANALYSING, WorkflowState.ANALYSING),
            new Rule(Transition.T18_BRD_DRAFTED, WorkflowState.ANALYSING, WorkflowState.BRD_DRAFTED),
            new Rule(Transition.T19_ANALYSIS_FAILED, WorkflowState.ANALYSING, WorkflowState.FAILED),
            new Rule(Transition.T20_QUEUE_REVIEW, WorkflowState.BRD_DRAFTED, WorkflowState.INTERNAL_REVIEW),
            // R1-M6 (TDD 9.3, 19).
            new Rule(Transition.T21_APPROVE_INTERNAL, WorkflowState.INTERNAL_REVIEW, WorkflowState.INTERNAL_APPROVED),
            new Rule(Transition.T22_REQUEST_REVISION, WorkflowState.INTERNAL_REVIEW, WorkflowState.REVISION_REQUESTED),
            new Rule(Transition.T23_REJECT, WorkflowState.INTERNAL_REVIEW, WorkflowState.REJECTED),
            new Rule(Transition.T24_REANALYSE_REVISION, WorkflowState.REVISION_REQUESTED, WorkflowState.ANALYSING),
            new Rule(Transition.T25_RELEASE_TO_CLIENT, WorkflowState.INTERNAL_APPROVED, WorkflowState.SHARED_WITH_CLIENT),
            new Rule(Transition.T27_CLIENT_CHANGES, WorkflowState.SHARED_WITH_CLIENT, WorkflowState.CLIENT_CHANGES_REQUESTED),
            new Rule(Transition.T28_REANALYSE_CLIENT_CHANGES, WorkflowState.CLIENT_CHANGES_REQUESTED,
                    WorkflowState.ANALYSING),
            // PRD section 6: CANCELLED is reachable from any non-terminal state; these are the states reachable so far.
            new Rule(Transition.T29_CANCEL, WorkflowState.DRAFT, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.SUBMITTED, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.MANUAL_INTAKE, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.REPO_VALIDATING, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.AWAITING_DOCUMENTS, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.AWAITING_DOCUMENT_DECISION, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.FAILED, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.ANALYSING, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.AWAITING_CLARIFICATION, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.BRD_DRAFTED, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.INTERNAL_REVIEW, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.REVISION_REQUESTED, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.INTERNAL_APPROVED, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.SHARED_WITH_CLIENT, WorkflowState.CANCELLED),
            new Rule(Transition.T29_CANCEL, WorkflowState.CLIENT_CHANGES_REQUESTED, WorkflowState.CANCELLED));

    private TransitionTable() {
    }

    /** The target state, or empty if the transition is not legal from {@code from}. */
    static Optional<WorkflowState> target(Transition transition, WorkflowState from) {
        return RULES.stream().filter(r -> r.transition() == transition && r.from() == from).map(Rule::to).findFirst();
    }
}
