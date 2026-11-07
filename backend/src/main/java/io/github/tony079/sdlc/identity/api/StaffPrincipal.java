package io.github.tony079.sdlc.identity.api;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

import io.github.tony079.sdlc.tenancy.api.TenantScopedPrincipal;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * An internal staff member after password and MFA (owner decisions O-06, O-11). The tenant is the assignment-gated
 * active tenant, or null until one is selected; it is re-validated against the assignment on every protected
 * operation. Values come from the database, never from the request.
 */
public record StaffPrincipal(UUID staffId, String email, String displayName, UUID activeTenantId,
                             String activeTenantName) implements TenantScopedPrincipal, AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public UUID tenantId() {
        return activeTenantId;
    }

    @Override
    public UUID userId() {
        return staffId;
    }

    @Override
    public String actorType() {
        return "STAFF";
    }

    @Override
    public String getName() {
        return staffId.toString();
    }

    public StaffPrincipal withActiveTenant(UUID tenantId, String tenantName) {
        return new StaffPrincipal(staffId, email, displayName, tenantId, tenantName);
    }
}
