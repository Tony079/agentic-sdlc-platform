package io.github.tony079.sdlc.workflow.api;

/** The Release 1 requirement states of PRD section 6, exactly. Shown to users as a read-only projection. */
public enum WorkflowState {
    DRAFT, SUBMITTED, MANUAL_INTAKE, REPO_VALIDATING, AWAITING_DOCUMENTS, AWAITING_DOCUMENT_DECISION, ANALYSING,
    AWAITING_CLARIFICATION, BRD_DRAFTED, INTERNAL_REVIEW, REVISION_REQUESTED, INTERNAL_APPROVED, SHARED_WITH_CLIENT,
    CLIENT_CHANGES_REQUESTED, REJECTED, FAILED, CANCELLED;

    /** Terminal states (PRD section 6): no transition leaves them. */
    public boolean isTerminal() {
        return this == CANCELLED || this == REJECTED;
    }
}
