package io.github.tony079.sdlc.workflow.api;

/**
 * Where a submitted requirement goes next (PRD section 6 routing from SUBMITTED). Derived from the requirement
 * kind at submission; the kind cannot change afterwards (decision Q-M3-5), so the route is stable.
 */
public enum Route {
    /**
     * EXISTING_REPOSITORY, and NEW_PRODUCT since GD-28: T02 to REPO_VALIDATING (a new product has no repositories, so
     * its preparation completes at once and the analysis reads the requirement and its documents).
     */
    REPOSITORY,
    /**
     * NEW_PRODUCT when sdlc.requirements.new-product-route=MANUAL_INTAKE: T03 to MANUAL_INTAKE (PRD 0.4); no extraction,
     * repository validation or BA job is scheduled.
     */
    MANUAL_INTAKE
}
