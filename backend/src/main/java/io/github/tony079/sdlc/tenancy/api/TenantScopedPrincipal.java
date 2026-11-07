package io.github.tony079.sdlc.tenancy.api;

import java.util.UUID;

/**
 * An authenticated principal acting inside one tenant (PRD section 4A). For a tenant user the tenant is fixed; for an
 * internal staff member it is the assignment-gated active tenant (owner decision O-06), or null when none is selected.
 */
public interface TenantScopedPrincipal {

    UUID tenantId();

    UUID userId();

    /** Audit actor type: USER for tenant users, STAFF for internal staff. */
    default String actorType() {
        return "USER";
    }
}
