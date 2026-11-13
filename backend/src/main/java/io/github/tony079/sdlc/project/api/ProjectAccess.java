package io.github.tony079.sdlc.project.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The signed-in client user's standing in a project, for other modules (FR-ACC-01). Requires an active transaction
 * with the user's tenant context. Row-level security enforces the same rules in the database (GD-13).
 */
public interface ProjectAccess {

    /** The current user's active role in the project, or empty when they are not a member (or it does not exist). */
    Optional<ProjectRole> roleOf(UUID projectId);

    /** True when the project exists in the current tenant and is not archived. */
    boolean isActive(UUID projectId);
}
