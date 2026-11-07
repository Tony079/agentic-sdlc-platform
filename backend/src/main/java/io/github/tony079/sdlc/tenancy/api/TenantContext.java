package io.github.tony079.sdlc.tenancy.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Source of the tenant context for database transactions.
 *
 * <p>The tenant comes only from the authenticated identity (PRD FR-AUTH-06), never from request
 * parameters, bodies or headers. The single exception is {@link #callAs}, used by trusted server code
 * that must act inside one tenant before a user is authenticated (credential lookup at login, development
 * seeding). It is never driven by an authenticated user's request input.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> SYSTEM_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Tenant of the current thread: an explicit system scope first, otherwise the authenticated principal. */
    public static Optional<UUID> currentTenant() {
        UUID explicit = SYSTEM_TENANT.get();
        if (explicit != null) {
            return Optional.of(explicit);
        }
        return principal().flatMap(p -> Optional.ofNullable(p.tenantId()));
    }

    /** User id of the authenticated principal, if any. */
    public static Optional<UUID> currentActor() {
        return principal().map(TenantScopedPrincipal::userId);
    }

    /** Audit actor type of the authenticated principal (USER or STAFF), or SYSTEM without one. */
    public static String currentActorType() {
        return principal().map(TenantScopedPrincipal::actorType).orElse("SYSTEM");
    }

    /** The current tenant, or an exception if none is set. Use where a missing tenant is a programming error. */
    public static UUID requireTenant() {
        return currentTenant().orElseThrow(() -> new IllegalStateException("No tenant context"));
    }

    /**
     * Runs trusted server code inside one tenant. Transactions must start inside the action: the tenant is
     * applied to the database connection when a transaction begins.
     */
    public static <T> T callAs(UUID tenantId, Supplier<T> action) {
        Objects.requireNonNull(tenantId, "tenantId");
        UUID previous = SYSTEM_TENANT.get();
        SYSTEM_TENANT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                SYSTEM_TENANT.remove();
            } else {
                SYSTEM_TENANT.set(previous);
            }
        }
    }

    private static Optional<TenantScopedPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof TenantScopedPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }
}
