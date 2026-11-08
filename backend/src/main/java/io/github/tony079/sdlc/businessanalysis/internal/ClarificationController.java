package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.ClarificationRepository.PendingDecision;
import io.github.tony079.sdlc.businessanalysis.internal.ClarificationService.ClarificationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** TDD 20.9 clarifications (client owner and staff) and document decisions. Identities come from the session only. */
@RestController
@Profile("!migrate & !provision")
class ClarificationController {

    private final ClarificationService service;

    ClarificationController(ClarificationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/clarifications")
    List<ClarificationView> inbox() {
        return service.ownerInbox();
    }

    @GetMapping("/api/v1/clarifications/{id}")
    ClarificationView detail(@PathVariable UUID id) {
        return service.ownerDetail(id);
    }

    @PostMapping("/api/v1/clarifications/{id}/answer")
    Map<String, String> answer(@PathVariable UUID id, @Valid @RequestBody TextRequest body) {
        return Map.of("status", service.ownerAnswer(id, body.text()));
    }

    @PostMapping("/api/v1/clarifications/{id}/reject")
    Map<String, String> reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return Map.of("status", service.ownerReject(id, body.reason()));
    }

    @PostMapping("/api/v1/requirements/{id}/documents/{documentId}/acknowledge-exclusion")
    ResponseEntity<Void> acknowledge(@PathVariable UUID id, @PathVariable UUID documentId,
                                     @Valid @RequestBody ReasonRequest body) {
        service.acknowledgeExclusion(id, documentId, body.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/staff/clarifications")
    List<ClarificationView> staffInbox() {
        return service.staffInbox();
    }

    @GetMapping("/api/v1/staff/clarifications/{id}")
    ClarificationView staffDetail(@PathVariable UUID id) {
        return service.staffDetail(id);
    }

    @PostMapping("/api/v1/staff/clarifications/{id}/answer")
    Map<String, String> staffAnswer(@PathVariable UUID id, @Valid @RequestBody TextRequest body) {
        return Map.of("status", service.staffAnswer(id, body.text()));
    }

    @PostMapping("/api/v1/staff/clarifications/{id}/reject")
    Map<String, String> staffReject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return Map.of("status", service.staffReject(id, body.reason()));
    }

    @PostMapping("/api/v1/staff/clarifications/{id}/request-info")
    Map<String, String> requestInfo(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return Map.of("status", service.requestInfo(id, body.reason()));
    }

    @PostMapping("/api/v1/staff/clarifications/{id}/reopen")
    Map<String, String> reopen(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return Map.of("status", service.reopen(id, body.reason()));
    }

    @PostMapping("/api/v1/staff/clarifications/{id}/reassign")
    Map<String, String> reassign(@PathVariable UUID id, @Valid @RequestBody ReassignRequest body) {
        return Map.of("status", service.reassign(id, body.role(), body.reason()));
    }

    @PostMapping("/api/v1/staff/brd-versions/{id}/clarifications")
    ResponseEntity<Map<String, UUID>> raise(@PathVariable UUID id, @Valid @RequestBody RaiseRequest body) {
        UUID created = service.raise(id, body.question(), body.whyItMatters(), body.assignedRole(),
                body.blocking() == null || body.blocking());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("clarificationId", created));
    }

    @GetMapping("/api/v1/staff/document-decisions")
    List<PendingDecision> pendingDecisions() {
        return service.pendingDecisions();
    }

    @PostMapping("/api/v1/staff/document-decisions")
    ResponseEntity<Void> exclude(@Valid @RequestBody DecisionRequest body) {
        service.exclude(body.versionId(), body.reason());
        return ResponseEntity.noContent().build();
    }

    record TextRequest(@Size(max = 8000) String text) {
    }

    record ReasonRequest(@Size(max = 2000) String reason) {
    }

    record ReassignRequest(@Size(max = 40) String role, @Size(max = 2000) String reason) {
    }

    record RaiseRequest(@Size(max = 2000) String question, @Size(max = 2000) String whyItMatters,
                        @Size(max = 40) String assignedRole, Boolean blocking) {
    }

    record DecisionRequest(UUID versionId, @Size(max = 1000) String reason) {
    }
}
