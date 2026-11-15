package io.github.tony079.sdlc.sourcerepo.api;

import java.util.Optional;
import java.util.UUID;

/**
 * GD-23 (API side): the code a requirement on an existing product is analysed against. The requirement names a project
 * environment (PROD by default); each round of sources holds every verified repository-and-branch pair of that
 * environment, pinned by the worker. Requires an active transaction in the requirement's tenant.
 */
public interface EnvironmentSources {

    /** The chosen environment if it belongs to the project, otherwise (chosen == null) the project's PROD environment. */
    Optional<UUID> resolve(UUID projectId, UUID chosen);

    /**
     * Opens the requirement's next round of sources from the environment's verified branches and returns how many it
     * holds. Refused (validation) when the environment has no verified branch. A round after the first is pinned by an
     * ENV_PIN job the worker enqueues on SourcesRoundOpened; the first is pinned by repository validation (T02).
     */
    int openRound(UUID requirementId, UUID environmentId);

    /** Whether the requirement already has sources (it was submitted). */
    boolean hasSources(UUID requirementId);
}
