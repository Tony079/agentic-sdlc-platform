package io.github.tony079.sdlc.identity.internal;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** O-25 tenant soft deletion: client request; staff verification, approval, rejection and restoration. */
@RestController
@ConditionalOnWebApplication
class TenantLifecycleController {

    private final TenantLifecycleService service;

    TenantLifecycleController(TenantLifecycleService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/tenant/deletion-requests")
    @ResponseStatus(HttpStatus.CREATED)
    TenantLifecycleService.RequestView request(@Valid @RequestBody ReasonBody body) {
        return service.requestDeletion(body.reason());
    }

    @GetMapping("/api/v1/tenant/deletion-requests")
    List<TenantLifecycleService.RequestView> clientList() {
        return service.clientRequests();
    }

    @GetMapping("/api/v1/staff/tenant-lifecycle-requests")
    List<TenantLifecycleService.RequestView> staffList() {
        return service.staffRequests();
    }

    @PostMapping("/api/v1/staff/tenant-lifecycle-requests/{id}/verify")
    TenantLifecycleService.RequestView verify(@PathVariable UUID id) {
        return service.verify(id);
    }

    @PostMapping("/api/v1/staff/tenant-lifecycle-requests/{id}/approve")
    TenantLifecycleService.RequestView approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    @PostMapping("/api/v1/staff/tenant-lifecycle-requests/{id}/reject")
    TenantLifecycleService.RequestView reject(@PathVariable UUID id, @Valid @RequestBody NoteBody body) {
        return service.reject(id, body.note());
    }

    @PostMapping("/api/v1/staff/tenants/{tenantId}/restoration-requests")
    @ResponseStatus(HttpStatus.CREATED)
    TenantLifecycleService.RequestView restore(@PathVariable UUID tenantId, @Valid @RequestBody ReasonBody body) {
        return service.requestRestoration(tenantId, body.reason());
    }

    record ReasonBody(@Size(max = 4000) String reason) {
    }

    record NoteBody(@Size(max = 2000) String note) {
    }
}
