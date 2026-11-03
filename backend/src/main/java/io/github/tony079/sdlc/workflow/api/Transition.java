package io.github.tony079.sdlc.workflow.api;

/**
 * Transitions enabled so far, named after TDD 9.3. Milestone 2 enables only T01 and T29; routing (T02/T03)
 * arrives with the transactional outbox in Milestone 3 (decision Q4). Any other request is illegal.
 */
public enum Transition {
    /** T01: DRAFT to SUBMITTED, by the requirement's owner. */
    T01_SUBMIT,
    /** T29: any non-terminal state to CANCELLED, by the requirement's owner, with a reason. */
    T29_CANCEL
}
