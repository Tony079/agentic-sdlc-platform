package io.github.tony079.sdlc.requirement.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Lookup of requirements for other modules (attachments, BRD view, change requests, repository links). Visibility is
 * the requirement API's: members of the requirement's project, restricted requirements only for their owner and the
 * project's Approvers (Garuda GD-05). Requires an active transaction with the user's tenant context.
 */
public interface RequirementDirectory {

    /** A visible requirement that the current user created (owner-only actions). */
    Optional<OwnedRequirement> findOwned(UUID requirementId);

    /** A requirement the current user may see, with their role in its project. */
    Optional<VisibleRequirement> findVisible(UUID requirementId);

    /**
     * The project of a requirement of the current tenant, for internal checks only (repository registration). Ids only:
     * no requirement content is returned, and it grants no visibility to the caller.
     */
    Optional<UUID> projectOf(UUID requirementId);

    record OwnedRequirement(UUID id, UUID projectId) {
    }

    /**
     * @param myRole the caller's role in the project (null for staff, who are not project members)
     */
    record VisibleRequirement(UUID id, UUID projectId, UUID ownerId, boolean ownedByMe,
                              io.github.tony079.sdlc.project.api.ProjectRole myRole) {

        public boolean canApprove() {
            return myRole != null && myRole.canApprove();
        }
    }
}
