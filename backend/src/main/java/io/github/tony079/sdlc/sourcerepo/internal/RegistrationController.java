package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.sourcerepo.internal.RegistrationRepository.PendingLink;
import io.github.tony079.sdlc.sourcerepo.internal.RegistrationRepository.RepositorySummary;
import io.github.tony079.sdlc.sourcerepo.internal.RegistrationService.Registered;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Repository registration (staff) and status (tenant users and staff). */
@RestController
class RegistrationController {

    private final RegistrationService service;

    RegistrationController(RegistrationService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/projects/{projectId}/repositories")
    ResponseEntity<Registered> register(@PathVariable UUID projectId, @Valid @RequestBody RegisterRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.register(projectId, body.linkId()));
    }

    @GetMapping("/api/v1/projects/{projectId}/repositories")
    List<RepositorySummary> list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    @GetMapping("/api/v1/staff/repository-links")
    List<PendingLink> pendingLinks() {
        return service.pendingLinks();
    }

    record RegisterRequest(@NotNull UUID linkId) {
    }
}
