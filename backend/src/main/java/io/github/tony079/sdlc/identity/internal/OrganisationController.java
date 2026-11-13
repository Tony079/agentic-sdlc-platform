package io.github.tony079.sdlc.identity.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.CurrentUser.TenantRole;
import io.github.tony079.sdlc.identity.internal.AccountMail.Kind;
import io.github.tony079.sdlc.identity.internal.OrganisationUserService.LinkResult;
import io.github.tony079.sdlc.identity.internal.OrganisationUserService.MembershipRequest;
import io.github.tony079.sdlc.identity.internal.OrganisationUserService.OrganisationSummary;
import io.github.tony079.sdlc.identity.internal.OrganisationUserService.UserSummary;
import io.github.tony079.sdlc.identity.internal.PublicAccountService.LinkDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organisation administration (FR-ORG-04..08) for Organisation Admins, and the public account-link endpoints. Tokens
 * travel only in POST bodies, never in URLs.
 */
@RestController
@ConditionalOnWebApplication
class OrganisationController {

    record InviteRequest(@NotNull @Size(max = 320) String email, @NotNull @Size(max = 200) String displayName,
                         @NotNull TenantRole role, @Size(max = 100) List<MembershipRequest> memberships) {
    }

    record RoleRequest(@NotNull TenantRole role) {
    }

    record TokenRequest(@NotNull @Size(max = 63) String organisation, @NotNull @Size(max = 64) String token) {
    }

    record CompleteRequest(@NotNull @Size(max = 63) String organisation, @NotNull @Size(max = 64) String token,
                           @NotNull @Size(max = 200) String password) {
    }

    record ResetRequest(@NotNull @Size(max = 63) String organisation, @NotNull @Size(max = 320) String email) {
    }

    private final OrganisationUserService users;
    private final PublicAccountService accounts;

    OrganisationController(OrganisationUserService users, PublicAccountService accounts) {
        this.users = users;
        this.accounts = accounts;
    }

    @GetMapping("/api/v1/organisation")
    OrganisationSummary summary() {
        return users.summary();
    }

    @GetMapping("/api/v1/organisation/users")
    List<UserSummary> list() {
        return users.list();
    }

    @PostMapping("/api/v1/organisation/users")
    @ResponseStatus(HttpStatus.CREATED)
    LinkResult invite(@Valid @RequestBody InviteRequest body) {
        return users.invite(body.email(), body.displayName(), body.role(), body.memberships());
    }

    @PostMapping("/api/v1/organisation/users/{id}/invitation/resend")
    LinkResult resend(@PathVariable UUID id) {
        return users.resendInvitation(id);
    }

    @PostMapping("/api/v1/organisation/users/{id}/invitation/revoke")
    ResponseEntity<Void> revoke(@PathVariable UUID id) {
        users.revokeInvitation(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/organisation/users/{id}/role")
    ResponseEntity<Void> role(@PathVariable UUID id, @Valid @RequestBody RoleRequest body) {
        users.changeRole(id, body.role());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/organisation/users/{id}/deactivate")
    ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        users.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/organisation/users/{id}/reactivate")
    ResponseEntity<Void> reactivate(@PathVariable UUID id) {
        users.reactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/organisation/users/{id}/mfa/reset")
    ResponseEntity<Void> resetMfa(@PathVariable UUID id) {
        users.resetMfa(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/organisation/users/{id}/password-reset")
    LinkResult passwordReset(@PathVariable UUID id) {
        return users.sendPasswordReset(id);
    }

    // --- public (not signed in) ---------------------------------------------------------------------------------

    @PostMapping("/api/v1/public/invitations/lookup")
    LinkDetails lookupInvitation(@Valid @RequestBody TokenRequest body) {
        return accounts.lookup(body.organisation(), body.token(), Kind.INVITE);
    }

    @PostMapping("/api/v1/public/invitations/accept")
    ResponseEntity<Void> accept(@Valid @RequestBody CompleteRequest body) {
        accounts.complete(body.organisation(), body.token(), body.password(), Kind.INVITE);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/public/password-resets")
    ResponseEntity<Void> requestReset(@Valid @RequestBody ResetRequest body) {
        accounts.requestReset(body.organisation(), body.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/v1/public/password-resets/lookup")
    LinkDetails lookupReset(@Valid @RequestBody TokenRequest body) {
        return accounts.lookup(body.organisation(), body.token(), Kind.PASSWORD_RESET);
    }

    @PostMapping("/api/v1/public/password-resets/complete")
    ResponseEntity<Void> completeReset(@Valid @RequestBody CompleteRequest body) {
        accounts.complete(body.organisation(), body.token(), body.password(), Kind.PASSWORD_RESET);
        return ResponseEntity.noContent().build();
    }
}
