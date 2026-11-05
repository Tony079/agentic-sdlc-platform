package io.github.tony079.sdlc.requirement.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Lookup of requirements visible to the current user, for other modules (for example attachments). Visibility is
 * the same as the requirement API: owner-only for both client roles (PRD section 4; C-18 open). Requires an active
 * transaction with the user's tenant context.
 */
public interface RequirementDirectory {

    Optional<OwnedRequirement> findOwned(UUID requirementId);

    record OwnedRequirement(UUID id, UUID projectId) {
    }
}
