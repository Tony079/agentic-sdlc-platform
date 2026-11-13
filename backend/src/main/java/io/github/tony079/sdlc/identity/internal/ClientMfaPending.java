package io.github.tony079.sdlc.identity.internal;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.CurrentUser;

/**
 * An Organisation Admin who passed the password step but not yet MFA (Garuda GD-08). Deliberately not a
 * TenantScopedPrincipal: it reaches no tenant data and holds the single authority ROLE_CLIENT_MFA_PENDING.
 */
record ClientMfaPending(CurrentUser user, boolean enrolled) implements Serializable {

    static final String AUTHORITY = "ROLE_CLIENT_MFA_PENDING";

    @Serial
    private static final long serialVersionUID = 1L;
}
