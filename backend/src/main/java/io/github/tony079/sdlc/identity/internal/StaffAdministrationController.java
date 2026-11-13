package io.github.tony079.sdlc.identity.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.identity.internal.StaffAdministrationService.Approved;
import io.github.tony079.sdlc.identity.internal.StaffAdministrationService.LinkDetails;
import io.github.tony079.sdlc.identity.internal.StaffAdministrationService.NewRequest;
import io.github.tony079.sdlc.identity.internal.StaffAdministrationService.Request;
import io.github.tony079.sdlc.identity.internal.StaffAdministrationService.StaffMember;
import io.github.tony079.sdlc.identity.internal.StaffAdministrationService.Tenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Garuda console staff administration (GD-15) and the public staff-link endpoints (token only in POST bodies). */
@RestController
@ConditionalOnWebApplication
class StaffAdministrationController {

    record LinkBody(@NotNull @Size(max = 64) String token) {
    }

    record CompleteBody(@NotNull @Size(max = 64) String token, @NotNull @Size(max = 200) String password) {
    }

    private final StaffAdministrationService admin;

    StaffAdministrationController(StaffAdministrationService admin) {
        this.admin = admin;
    }

    @GetMapping("/api/v1/staff/admin/staff")
    List<StaffMember> staff() {
        return admin.staff();
    }

    @GetMapping("/api/v1/staff/admin/tenants")
    List<Tenant> tenants() {
        return admin.tenants();
    }

    @GetMapping("/api/v1/staff/admin/requests")
    List<Request> requests() {
        return admin.requests();
    }

    @PostMapping("/api/v1/staff/admin/requests")
    @ResponseStatus(HttpStatus.CREATED)
    Request create(@Valid @RequestBody NewRequest body) {
        return admin.create(body);
    }

    @PostMapping("/api/v1/staff/admin/requests/{id}/approve")
    Approved approve(@PathVariable UUID id) {
        return admin.approve(id);
    }

    @PostMapping("/api/v1/staff/admin/requests/{id}/reject")
    Request reject(@PathVariable UUID id) {
        return admin.decide(id, false);
    }

    @PostMapping("/api/v1/staff/admin/requests/{id}/cancel")
    Request cancel(@PathVariable UUID id) {
        return admin.decide(id, true);
    }

    @PostMapping("/api/v1/staff/admin/staff/{id}/invitation/resend")
    Approved resend(@PathVariable UUID id) {
        return admin.resendInvitation(id);
    }

    @PostMapping("/api/v1/public/staff-invitations/lookup")
    LinkDetails lookupInvitation(@Valid @RequestBody LinkBody body) {
        return admin.lookup(body.token(), "INVITE");
    }

    @PostMapping("/api/v1/public/staff-invitations/accept")
    ResponseEntity<Void> accept(@Valid @RequestBody CompleteBody body) {
        admin.complete(body.token(), body.password(), "INVITE");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/public/staff-password-resets/lookup")
    LinkDetails lookupReset(@Valid @RequestBody LinkBody body) {
        return admin.lookup(body.token(), "PASSWORD_RESET");
    }

    @PostMapping("/api/v1/public/staff-password-resets/complete")
    ResponseEntity<Void> completeReset(@Valid @RequestBody CompleteBody body) {
        admin.complete(body.token(), body.password(), "PASSWORD_RESET");
        return ResponseEntity.noContent().build();
    }
}
