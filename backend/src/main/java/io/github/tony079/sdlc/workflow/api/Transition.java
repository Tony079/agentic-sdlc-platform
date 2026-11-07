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
    /** T06: REPO_VALIDATING to AWAITING_DOCUMENTS, by the engine once the repository is validated and pinned. */
    T06_REPO_VALIDATED(true),
    /** T07: REPO_VALIDATING to AWAITING_DOCUMENT_DECISION, by the engine, when an attachment needs a decision. */
    T07_REPO_VALIDATED_DOCUMENT_DECISION(true),
    /** T09: REPO_VALIDATING to FAILED, by the engine when the validation job is dead. No retry path (O-27). */
    T09_REPO_VALIDATION_FAILED(true),
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
