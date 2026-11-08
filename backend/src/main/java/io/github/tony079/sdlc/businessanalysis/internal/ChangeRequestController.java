package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.Map;
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

/** C-09 change requests and C-16 priorities (owner, Business Analyst, Business Reviewer, Client Approver). */
@RestController
@ConditionalOnWebApplication
class ChangeRequestController {

    private final ChangeRequestService service;

    ChangeRequestController(ChangeRequestService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/requirements/{id}/change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    ChangeRequestService.ChangeRequestView submit(@PathVariable UUID id, @Valid @RequestBody SubmitBody body) {
        return service.submit(id, body.requestedChange(), body.justification(), body.expectedImpact(), body.proposed());
    }

    @GetMapping("/api/v1/requirements/{id}/change-requests")
    List<ChangeRequestService.ChangeRequestView> ownerList(@PathVariable UUID id) {
        return service.ownerList(id);
    }

    @GetMapping("/api/v1/staff/change-requests")
    List<ChangeRequestService.ChangeRequestView> staffQueue() {
        return service.staffQueue();
    }

    @PostMapping("/api/v1/staff/change-requests/{id}/assessment")
    ChangeRequestService.ChangeRequestView assess(@PathVariable UUID id, @Valid @RequestBody AssessBody body) {
        return service.assess(id, body.scopeAssessment(), body.effortEstimate(), body.timelineImpact(),
                body.technicalImpact());
    }

    @PostMapping("/api/v1/staff/change-requests/{id}/diff/{field}/priority")
    ChangeRequestService.ChangeRequestView priority(@PathVariable UUID id, @PathVariable String field,
                                                    @Valid @RequestBody PriorityBody body) {
        return service.adjustPriority(id, field, body.priority(), body.reason());
    }

    @PostMapping("/api/v1/staff/change-requests/{id}/review")
    ChangeRequestService.ChangeRequestView review(@PathVariable UUID id, @Valid @RequestBody DecisionBody body) {
        return service.review(id, "APPROVE".equals(body.decision()), body.note());
    }

    @GetMapping("/api/v1/change-requests")
    List<ChangeRequestService.ChangeRequestView> approverQueue() {
        return service.approverQueue();
    }

    @PostMapping("/api/v1/change-requests/{id}/decision")
    ChangeRequestService.ChangeRequestView decide(@PathVariable UUID id, @Valid @RequestBody DecisionBody body) {
        return service.decide(id, "ACCEPT".equals(body.decision()), body.note());
    }

    record SubmitBody(@Size(max = 6000) String requestedChange, @Size(max = 6000) String justification,
                      @Size(max = 6000) String expectedImpact, Map<String, String> proposed) {
    }

    record AssessBody(@Size(max = 6000) String scopeAssessment, @Size(max = 2000) String effortEstimate,
                      @Size(max = 2000) String timelineImpact, @Size(max = 6000) String technicalImpact) {
    }

    record PriorityBody(@Size(max = 20) String priority, @Size(max = 3000) String reason) {
    }

    record DecisionBody(@Size(max = 20) String decision, @Size(max = 3000) String note) {
    }
}
