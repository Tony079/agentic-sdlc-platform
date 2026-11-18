package io.github.tony079.sdlc.project.api;

import java.util.List;
import java.util.UUID;

/**
 * The signed-in client user's standing in a project, for other modules (FR-ACC-01). Requires an active transaction
 * with the user's tenant context. Row-level security enforces the same rules in the database (GD-13).
 */
public interface ProjectAccess {

    /** The current user's active roles in the project; empty when they are not a member (or it does not exist). */
    List<ProjectRole> rolesOf(UUID projectId);

    /** True when the project exists in the current tenant and is not archived. */
    boolean isActive(UUID projectId);
}
