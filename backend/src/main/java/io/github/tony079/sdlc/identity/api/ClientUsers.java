package io.github.tony079.sdlc.identity.api;

import java.util.Optional;

import io.github.tony079.sdlc.common.api.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Access to the signed-in organisation user (never taken from the request). */
public final class ClientUsers {

    private ClientUsers() {
    }

    public static Optional<CurrentUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CurrentUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /** The signed-in organisation user; 401 when there is none. */
    public static CurrentUser require() {
        return current().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required."));
    }

    /** The signed-in Organisation Admin; 403 for any other organisation user. */
    public static CurrentUser requireOrgAdmin() {
        CurrentUser user = require();
        if (!user.orgAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORG_ADMIN_REQUIRED",
                    "Only an Organisation Admin can do this.");
        }
        return user;
    }
}
