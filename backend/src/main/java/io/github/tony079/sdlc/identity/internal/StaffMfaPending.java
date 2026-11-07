package io.github.tony079.sdlc.identity.internal;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * A staff member who passed the password check but not yet MFA (owner decision O-11). Deliberately not a
 * TenantScopedPrincipal: it never carries a tenant and has the single authority ROLE_STAFF_MFA_PENDING.
 */
record StaffMfaPending(UUID staffId, boolean enrolled) implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String AUTHORITY = "ROLE_STAFF_MFA_PENDING";

    @Override
    public String getName() {
        return staffId.toString();
    }
}
