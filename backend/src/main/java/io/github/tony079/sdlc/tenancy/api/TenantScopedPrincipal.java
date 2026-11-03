package io.github.tony079.sdlc.tenancy.api;

import java.util.UUID;

/** An authenticated principal that belongs to exactly one tenant (PRD section 4A). */
public interface TenantScopedPrincipal {

    UUID tenantId();

    UUID userId();
}
