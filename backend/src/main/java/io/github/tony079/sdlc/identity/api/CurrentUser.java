package io.github.tony079.sdlc.identity.api;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

import io.github.tony079.sdlc.tenancy.api.TenantScopedPrincipal;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The authenticated tenant user, stored in the server-side session. Every value comes from the database
 * at login, never from the request.
 */
public record CurrentUser(UUID userId, UUID tenantId, String tenantName, String email, String displayName,
                          TenantRole role) implements TenantScopedPrincipal, AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Session index name: the opaque user id, not the email. */
    @Override
    public String getName() {
        return userId.toString();
    }

    /** Tenant roles of PRD section 4. Platform/staff roles are not part of Milestone 1 (decision O-06). */
    public enum TenantRole { CLIENT_USER, CLIENT_APPROVER }
}
