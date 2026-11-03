package io.github.tony079.sdlc.identity.internal;

import io.github.tony079.sdlc.identity.api.CurrentUser;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication
class MeController {

    @GetMapping("/api/v1/me")
    MeResponse me(@AuthenticationPrincipal CurrentUser user) {
        return MeResponse.of(user);
    }
}
