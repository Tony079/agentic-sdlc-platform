package io.github.tony079.sdlc.identity.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.identity.internal.OrganisationOnboardingService.Approved;
import io.github.tony079.sdlc.identity.internal.OrganisationOnboardingService.OnboardingRequest;
import io.github.tony079.sdlc.identity.internal.OrganisationOnboardingService.StatusRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Garuda staff: organisation onboarding, suspension and reactivation, each by two people (FR-ORG-01..03, GD-03). */
@RestController
@ConditionalOnWebApplication
class OrganisationOnboardingController {

    record OnboardingBody(@NotNull @Size(max = 200) String organisationName, @NotNull @Size(max = 63) String slug,
                          @NotNull @Size(max = 320) String adminEmail, @NotNull @Size(max = 200) String adminDisplayName) {
    }

    record NoteBody(@Size(max = 2000) String note) {
    }

    record StatusBody(@NotNull String kind, @NotNull @Size(max = 2000) String reason) {
    }

    private final OrganisationOnboardingService onboarding;

    OrganisationOnboardingController(OrganisationOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @GetMapping("/api/v1/staff/organisation-requests")
    List<OnboardingRequest> list() {
        return onboarding.listOnboarding();
    }

    @PostMapping("/api/v1/staff/organisation-requests")
    @ResponseStatus(HttpStatus.CREATED)
    OnboardingRequest request(@Valid @RequestBody OnboardingBody body) {
        return onboarding.requestOnboarding(body.organisationName(), body.slug(), body.adminEmail(),
                body.adminDisplayName());
    }

    @PostMapping("/api/v1/staff/organisation-requests/{id}/approve")
    Approved approve(@PathVariable UUID id) {
        return onboarding.approveOnboarding(id);
    }

    @PostMapping("/api/v1/staff/organisation-requests/{id}/reject")
    OnboardingRequest reject(@PathVariable UUID id, @Valid @RequestBody(required = false) NoteBody body) {
        return onboarding.decideOnboarding(id, false, body == null ? null : body.note());
    }

    @PostMapping("/api/v1/staff/organisation-requests/{id}/cancel")
    OnboardingRequest cancel(@PathVariable UUID id) {
        return onboarding.decideOnboarding(id, true, null);
    }

    @GetMapping("/api/v1/staff/organisation-status-requests")
    List<StatusRequest> statusRequests() {
        return onboarding.listStatusRequests();
    }

    @PostMapping("/api/v1/staff/organisations/{tenantId}/status-requests")
    @ResponseStatus(HttpStatus.CREATED)
    StatusRequest requestStatus(@PathVariable UUID tenantId, @Valid @RequestBody StatusBody body) {
        return onboarding.requestStatusChange(tenantId, body.kind(), body.reason());
    }

    @PostMapping("/api/v1/staff/organisation-status-requests/{id}/approve")
    StatusRequest approveStatus(@PathVariable UUID id) {
        return onboarding.approveStatusChange(id);
    }

    @PostMapping("/api/v1/staff/organisation-status-requests/{id}/reject")
    StatusRequest rejectStatus(@PathVariable UUID id) {
        return onboarding.decideStatusChange(id, false);
    }

    @PostMapping("/api/v1/staff/organisation-status-requests/{id}/cancel")
    StatusRequest cancelStatus(@PathVariable UUID id) {
        return onboarding.decideStatusChange(id, true);
    }
}
