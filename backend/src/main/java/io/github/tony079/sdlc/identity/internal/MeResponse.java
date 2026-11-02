package io.github.tony079.sdlc.identity.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.CurrentUser;

/** GET /api/v1/me. The UI uses roles for navigation only; the server enforces every permission. */
record MeResponse(UUID userId, String displayName, String email, Tenant tenant, List<String> roles) {

    record Tenant(UUID id, String name) {
    }

    static MeResponse of(CurrentUser user) {
        return new MeResponse(user.userId(), user.displayName(), user.email(),
                new Tenant(user.tenantId(), user.tenantName()), List.of(user.role().name()));
    }
}
