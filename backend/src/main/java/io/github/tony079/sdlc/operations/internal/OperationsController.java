package io.github.tony079.sdlc.operations.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** O-08 feature flags and the operations overview (staff with FEATURE_FLAG_ACK; platform level). */
@RestController
@ConditionalOnWebApplication
class OperationsController {

    private final OperationsService service;

    OperationsController(OperationsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/staff/operations")
    Map<String, Object> overview() {
        return service.overview();
    }

    @GetMapping("/api/v1/staff/feature-flags")
    List<OperationsService.Flag> flags() {
        return service.flags();
    }

    @PutMapping("/api/v1/staff/feature-flags/{name}/{environment}")
    OperationsService.FlagChange change(@PathVariable String name, @PathVariable String environment,
                                        @Valid @RequestBody ChangeBody body) {
        return service.change(name, environment, body.value(), body.reason());
    }

    @PostMapping("/api/v1/staff/feature-flag-changes/{id}/acknowledge")
    OperationsService.FlagChange acknowledge(@PathVariable UUID id) {
        return service.acknowledge(id);
    }

    record ChangeBody(@Size(max = 200) String value, @Size(max = 2000) String reason) {
    }
}
