package io.github.tony079.sdlc.identity.api;

import java.util.UUID;

/**
 * Authorisation checks for staff operations, re-evaluated against the database on every call (owner decision O-06):
 * the caller must be a staff member who passed MFA, is still ACTIVE, has an active tenant whose assignment is still
 * active and, where required, holds the capability. Requires an active transaction. Throws a 403 ApiException and
 * records a security event otherwise.
 */
public interface StaffAccess {

    /** The verified staff member and the active tenant, for an operation needing only an assignment. */
    StaffContext requireActiveTenant();

    /** As {@link #requireActiveTenant()}, and the capability must be held. */
    StaffContext requireCapability(StaffCapability capability);

    /**
     * For operations on a named tenant regardless of its status (tenant soft deletion and restoration, O-25): the
     * caller must be an active staff member with MFA, hold {@code capability} and have an unrevoked assignment to
     * {@code tenantId}. The session's active tenant is not used. Returns the staff id.
     */
    UUID requireCapabilityForTenant(StaffCapability capability, UUID tenantId);

    record StaffContext(UUID staffId, UUID tenantId) {
    }
}
