package io.github.tony079.sdlc.project.api;

import java.util.List;
import java.util.UUID;

/**
 * Membership administration for other modules (user invitations add the invitee to projects). Every call acts as the
 * signed-in Organisation Admin and is refused for anyone else (FR-ORG-05/06; enforced again by the database).
 */
public interface ProjectAdministration {

    /** Adds a user of the organisation to an active project with a role. */
    void addMember(UUID projectId, UUID userId, ProjectRole role);

    /** The user's active memberships, for the Organisation Admin's user list. */
    List<Membership> membershipsOf(UUID userId);

    record Membership(UUID memberId, UUID projectId, String projectName, ProjectRole role) {
    }
}
