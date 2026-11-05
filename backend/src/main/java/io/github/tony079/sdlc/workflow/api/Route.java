package io.github.tony079.sdlc.workflow.api;

/**
 * Where a submitted requirement goes next (PRD section 6 routing from SUBMITTED). Derived from the requirement
 * kind at submission; the kind cannot change afterwards (decision Q-M3-5), so the route is stable.
 */
public enum Route {
    /** EXISTING_REPOSITORY: T02 to REPO_VALIDATING. */
    REPOSITORY,
    /** NEW_PRODUCT: T03 to MANUAL_INTAKE (PRD 0.4); no extraction, repository validation or BA job is scheduled. */
    MANUAL_INTAKE
}
