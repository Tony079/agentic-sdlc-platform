package io.github.tony079.sdlc.tenancy.api;

import java.util.Optional;
import java.util.UUID;

/** Read access to the global tenant registry. */
public interface TenantLookup {

    /** An ACTIVE tenant by its slug. Requires an active transaction. */
    Optional<TenantRef> findActiveBySlug(String slug);

    record TenantRef(UUID id, String slug, String name) {
    }
}
