package io.github.tony079.sdlc.identity.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.identity.internal.OrganisationAdminRequestService.Approved;
import io.github.tony079.sdlc.identity.internal.OrganisationAdminRequestService.NewRequest;
import io.github.tony079.sdlc.identity.internal.OrganisationAdminRequestService.Record;
import io.github.tony079.sdlc.identity.internal.OrganisationAdminRequestService.Request;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Garuda Admins: an organisation's record and Organisation Admins, each change by two people (GD-16, GD-17). */
@RestController
@ConditionalOnWebApplication
class OrganisationAdminRequestController {

    private final OrganisationAdminRequestService service;

    OrganisationAdminRequestController(OrganisationAdminRequestService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/staff/organisations/{tenantId}/record")
    Record record(@PathVariable UUID tenantId) {
        return service.record(tenantId);
    }

    @PostMapping("/api/v1/staff/organisations/{tenantId}/admin-requests")
    @ResponseStatus(HttpStatus.CREATED)
    Request create(@PathVariable UUID tenantId, @RequestBody NewRequest body) {
        return service.create(tenantId, body);
    }

    @GetMapping("/api/v1/staff/organisation-admin-requests")
    List<Request> list() {
        return service.list();
    }

    @PostMapping("/api/v1/staff/organisation-admin-requests/{id}/approve")
    Approved approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    @PostMapping("/api/v1/staff/organisation-admin-requests/{id}/reject")
    Request reject(@PathVariable UUID id) {
        return service.decide(id, false);
    }

    @PostMapping("/api/v1/staff/organisation-admin-requests/{id}/cancel")
    Request cancel(@PathVariable UUID id) {
        return service.decide(id, true);
    }
}
