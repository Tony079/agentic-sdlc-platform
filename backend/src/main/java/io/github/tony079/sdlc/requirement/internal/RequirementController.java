package io.github.tony079.sdlc.requirement.internal;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.requirement.api.RequirementView.Detail;
import io.github.tony079.sdlc.requirement.api.RequirementView.Revision;
import io.github.tony079.sdlc.requirement.api.RequirementView.SummaryPage;
import io.github.tony079.sdlc.workflow.api.TimelineEntry;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Requirement API (/api/v1/requirements). The tenant and the owner come from the session only. Mutations of an
 * existing requirement require If-Match with the version from the ETag (optimistic locking).
 */
@RestController
@RequestMapping("/api/v1/requirements")
@ConditionalOnWebApplication
class RequirementController {

    private final RequirementService requirements;

    RequirementController(RequirementService requirements) {
        this.requirements = requirements;
    }

    record CreateRequest(UUID projectId, String kind, String title, String description, String businessObjective,
                         String expectedOutcomes, String acceptanceCriteria, String repositoryUrl, String constraints,
                         String integrations) {

        RequirementFields fields() {
            return RequirementFields.normalized(kind, title, description, businessObjective, expectedOutcomes,
                    acceptanceCriteria, repositoryUrl, constraints, integrations);
        }
    }

    record UpdateRequest(String kind, String title, String description, String businessObjective,
                         String expectedOutcomes, String acceptanceCriteria, String repositoryUrl, String constraints,
                         String integrations) {

        RequirementFields fields() {
            return RequirementFields.normalized(kind, title, description, businessObjective, expectedOutcomes,
                    acceptanceCriteria, repositoryUrl, constraints, integrations);
        }
    }

    record CancelRequest(String reason) {
    }

    @PostMapping
    ResponseEntity<Detail> create(@RequestBody CreateRequest body,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        RequirementService.CreateResult result =
                requirements.create(body.projectId(), body.fields(), parseIdempotencyKey(idempotencyKey));
        Detail detail = result.detail();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/requirements/" + detail.id()))
                .eTag(Long.toString(detail.version()));
        if (result.replayed()) {
            response.header("Idempotency-Replayed", "true");
        }
        return response.body(detail);
    }

    @GetMapping
    SummaryPage list(@RequestParam(required = false) WorkflowState state,
                     @RequestParam(required = false) UUID projectId,
                     @RequestParam(required = false) String cursor,
                     @RequestParam(required = false) Integer limit) {
        return requirements.list(state, projectId, cursor, limit);
    }

    @GetMapping("/{id}")
    ResponseEntity<Detail> get(@PathVariable UUID id) {
        return withETag(requirements.get(id));
    }

    @GetMapping("/{id}/revisions/{revisionNo}")
    Revision revision(@PathVariable UUID id, @PathVariable int revisionNo) {
        return requirements.revision(id, revisionNo);
    }

    @GetMapping("/{id}/timeline")
    List<TimelineEntry> timeline(@PathVariable UUID id) {
        return requirements.timeline(id);
    }

    /** Audit history of the requirement and its documents, clarifications and BRD versions (content-free). */
    @GetMapping("/{id}/audit")
    List<io.github.tony079.sdlc.audit.api.AuditTrail.AuditEntry> audit(@PathVariable UUID id) {
        return requirements.auditHistory(id);
    }

    @PutMapping("/{id}")
    ResponseEntity<Detail> update(@PathVariable UUID id, @RequestBody UpdateRequest body,
                                  @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return withETag(requirements.update(id, expectedVersion(ifMatch), body.fields()));
    }

    @PostMapping("/{id}/submit")
    ResponseEntity<Detail> submit(@PathVariable UUID id,
                                  @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return withETag(requirements.submit(id, expectedVersion(ifMatch)));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<Detail> cancel(@PathVariable UUID id, @RequestBody CancelRequest body,
                                  @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return withETag(requirements.cancel(id, expectedVersion(ifMatch), body.reason()));
    }

    private static ResponseEntity<Detail> withETag(Detail detail) {
        return ResponseEntity.ok().eTag(Long.toString(detail.version())).body(detail);
    }

    /** Accepts "3", "\"3\"" or W/"3". Missing gives 428. */
    static long expectedVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.preconditionRequired();
        }
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            long version = Long.parseLong(value);
            if (version < 0) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException e) {
            throw ApiException.validation(List.of(new FieldError("If-Match", "INVALID_FORMAT")));
        }
    }

    private static UUID parseIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(List.of(new FieldError("Idempotency-Key", "INVALID_FORMAT")));
        }
    }
}
