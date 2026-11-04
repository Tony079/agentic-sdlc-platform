package io.github.tony079.sdlc.tenancy.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to the global tenant registry. */
public interface TenantLookup {

    /** An ACTIVE tenant by its slug. Requires an active transaction. */
    Optional<TenantRef> findActiveBySlug(String slug);

    /**
     * Ids of all ACTIVE tenants, for trusted background processing that then works inside one tenant at a time
     * (decision Q-M3-2). Reads only the id and status columns of the global registry. Requires a transaction.
     */
    List<UUID> activeTenantIds();

    record TenantRef(UUID id, String slug, String name) {
    }
}
