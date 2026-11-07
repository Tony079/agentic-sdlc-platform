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

    record StaffContext(UUID staffId, UUID tenantId) {
    }
}
