package io.github.tony079.sdlc.workflow.api;

/**
 * Transitions enabled so far, named after TDD 9.3. Any other request is illegal. T02 and T03 are engine
 * transitions: no user request can perform them (the database refuses them with a user actor).
 */
public enum Transition {
    /** T01: DRAFT to SUBMITTED, by the requirement's owner. Publishes RequirementSubmitted. */
    T01_SUBMIT(false),
    /** T02: SUBMITTED to REPO_VALIDATING, by the engine on RequirementSubmitted, kind EXISTING_REPOSITORY. */
    T02_ROUTE_REPOSITORY(true),
    /** T03: SUBMITTED to MANUAL_INTAKE, by the engine on RequirementSubmitted, kind NEW_PRODUCT. */
    T03_ROUTE_MANUAL_INTAKE(true),
    /**
     * T04: MANUAL_INTAKE to REPO_VALIDATING, by a Business Reviewer (C-06/C-07, owner decision 2026-11-08). Needs an
     * authorised repository link; creates the converted revision (FR-REQ-11) and schedules repository validation.
     */
    T04_RESUME_MANUAL_INTAKE(false),
    /** T05: MANUAL_INTAKE to CANCELLED, by a Business Reviewer, with a reason. Publishes RequirementCancelled. */
    T05_CLOSE_MANUAL_INTAKE(false),
    /** T06: REPO_VALIDATING to AWAITING_DOCUMENTS, by the engine once the repository is validated and pinned. */
    T06_REPO_VALIDATED(true),
    /** T07: REPO_VALIDATING to AWAITING_DOCUMENT_DECISION, by the engine, when an attachment needs a decision. */
    T07_REPO_VALIDATED_DOCUMENT_DECISION(true),
    /** T09: REPO_VALIDATING to FAILED, by the engine when the validation job is dead. No retry path (O-27). */
    T09_REPO_VALIDATION_FAILED(true),
    /** T10: AWAITING_DOCUMENTS to AWAITING_DOCUMENT_DECISION, by the engine when a document needs a decision. */
    T10_DOCUMENT_DECISION_NEEDED(true),
    /** T12: AWAITING_DOCUMENT_DECISION to AWAITING_DOCUMENTS, by the engine once decisions are recorded. */
    T12_DOCUMENT_DECISION_RECORDED(true),
    /** T13: AWAITING_DOCUMENT_DECISION to ANALYSING, by the engine when decisions are recorded and guard 9.4 holds. */
    T13_START_ANALYSIS_AFTER_DECISION(true),
    /** T16: ANALYSING to AWAITING_CLARIFICATION, by the engine when only blocked work remains. */
    T16_AWAIT_CLARIFICATION(true),
    /** T17: AWAITING_CLARIFICATION to ANALYSING, by the engine when no blocking clarification remains. */
    T17_RESUME_ANALYSIS(true),
    /** T11: AWAITING_DOCUMENTS to ANALYSING, by the engine when guard 9.4 holds; freezes the input set. */
    T11_START_ANALYSIS(true),
    /** T14: ANALYSING to AWAITING_DOCUMENTS, by the engine: inputs changed and the guard no longer holds. */
    T14_ANALYSIS_STALE_WAIT(true),
    /** T15: ANALYSING to ANALYSING, by the engine: inputs changed, guard holds, new execution and input set. */
    T15_ANALYSIS_RESTART(true),
    /** T18: ANALYSING to BRD_DRAFTED, by the engine only through the commit step (Tx C). */
    T18_BRD_DRAFTED(true),
    /** T19: ANALYSING to FAILED, by the engine when the BA job is dead or fatal. */
    T19_ANALYSIS_FAILED(true),
    /** T20: BRD_DRAFTED to INTERNAL_REVIEW, by the engine once the BRD version is complete. */
    T20_QUEUE_REVIEW(true),
    /** T21: INTERNAL_REVIEW to INTERNAL_APPROVED, by a Business Reviewer (staff), with approval T1. */
    T21_APPROVE_INTERNAL(false),
    /** T22: INTERNAL_REVIEW to REVISION_REQUESTED, by a Business Reviewer with correction notes. */
    T22_REQUEST_REVISION(false),
    /** T23: INTERNAL_REVIEW to REJECTED, by a Business Reviewer with a reason. */
    T23_REJECT(false),
    /** T24: REVISION_REQUESTED to ANALYSING, by the engine with a new input set including the notes. */
    T24_REANALYSE_REVISION(true),
    /** T25: INTERNAL_APPROVED to SHARED_WITH_CLIENT, by the engine (release T2, separate from T1). */
    T25_RELEASE_TO_CLIENT(true),
    /**
     * T26: FAILED to ANALYSING, by the engine, after an operator retry request (O-27) was re-validated. BA jobs only;
     * never reachable through the ordinary analysis start.
     */
    T26_ADMIN_RETRY(true),
    /** T27: SHARED_WITH_CLIENT to CLIENT_CHANGES_REQUESTED, by the client (C-09 provisional: requirement owner). */
    T27_CLIENT_CHANGES(false),
    /** T28: CLIENT_CHANGES_REQUESTED to ANALYSING, by the engine with the client's request as input. */
    T28_REANALYSE_CLIENT_CHANGES(true),
    /** T29: a non-terminal state to CANCELLED, by the requirement's owner, with a reason. */
    T29_CANCEL(false);

    private final boolean engineOnly;

    Transition(boolean engineOnly) {
        this.engineOnly = engineOnly;
    }

    public boolean engineOnly() {
        return engineOnly;
    }
}
