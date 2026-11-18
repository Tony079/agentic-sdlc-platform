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
     * @param myRoles the caller's roles in the project (empty for staff, who are not project members)
     */
    record VisibleRequirement(UUID id, UUID projectId, UUID ownerId, boolean ownedByMe,
                              java.util.List<io.github.tony079.sdlc.project.api.ProjectRole> myRoles) {

        public boolean canApprove() {
            return io.github.tony079.sdlc.project.api.ProjectRole.canApprove(myRoles);
        }

        /** GD-27: owns the Technical design stage in the project. */
        public boolean technicalApprover() {
            return myRoles.contains(io.github.tony079.sdlc.project.api.ProjectRole.TECHNICAL_APPROVER);
        }
    }
}
