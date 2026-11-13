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

    /** True for an Organisation Admin (GD-02). What a member may do inside a project comes from project roles. */
    public boolean orgAdmin() {
        return role == TenantRole.ORG_ADMIN;
    }

    /**
     * Organisation-level role (Garuda decision GD-02): ORG_ADMIN administers the organisation; MEMBER has only the
     * project roles assigned to them. Both carry the Spring authority ROLE_CLIENT; admins also ROLE_ORG_ADMIN.
     */
    public enum TenantRole { ORG_ADMIN, MEMBER }

    /** Spring authorities of this user. */
    public java.util.List<String> authorities() {
        return orgAdmin() ? java.util.List.of("ROLE_CLIENT", "ROLE_ORG_ADMIN") : java.util.List.of("ROLE_CLIENT");
    }
}
