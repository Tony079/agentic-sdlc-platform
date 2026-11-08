package io.github.tony079.sdlc.requirement.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Business Reviewer manual-intake endpoints (C-06/C-07). Staff session, active tenant, BUSINESS_REVIEWER. */
@RestController
@ConditionalOnWebApplication
class ManualIntakeController {

    private final ManualIntakeService service;

    ManualIntakeController(ManualIntakeService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/staff/manual-intake")
    List<RequirementRepository.ManualIntakeEntry> queue() {
        return service.queue();
    }

    @PostMapping("/api/v1/staff/requirements/{id}/resume")
    Map<String, String> resume(@PathVariable UUID id, @Valid @RequestBody ResumeRequest body) {
        return Map.of("state", service.resume(id, body.expectedRevisionNo(), body.reason()).name());
    }

    @PostMapping("/api/v1/staff/requirements/{id}/close")
    Map<String, String> close(@PathVariable UUID id, @Valid @RequestBody CloseRequest body) {
        return Map.of("state", service.close(id, body.reason()).name());
    }

    record ResumeRequest(Integer expectedRevisionNo, @Size(max = 2000) String reason) {
    }

    record CloseRequest(@Size(max = 2000) String reason) {
    }
}
