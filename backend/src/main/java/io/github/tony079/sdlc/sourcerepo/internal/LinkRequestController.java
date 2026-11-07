package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.UUID;

import io.github.tony079.sdlc.sourcerepo.internal.LinkRequestService.Issued;
import io.github.tony079.sdlc.sourcerepo.internal.LinkRequestService.LinkStatus;
import io.github.tony079.sdlc.sourcerepo.internal.LinkRequestService.Redeemed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** C-18 link requests. Tenant and user come only from the session; the token is never logged. */
@RestController
class LinkRequestController {

    private final LinkRequestService service;

    LinkRequestController(LinkRequestService service) {
        this.service = service;
    }

    /** The token appears in this response only, once, and is not cached. */
    @PostMapping("/api/v1/requirements/{requirementId}/repository-link-requests")
    ResponseEntity<Issued> issue(@PathVariable UUID requirementId, @Valid @RequestBody(required = false) IssueRequest body) {
        Issued issued = service.issue(requirementId, body == null ? null : body.expiresInHours());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(issued);
    }

    @GetMapping("/api/v1/requirements/{requirementId}/repository-link")
    LinkStatus status(@PathVariable UUID requirementId) {
        return service.status(requirementId);
    }

    @DeleteMapping("/api/v1/repository-link-requests/{requestId}")
    ResponseEntity<Void> revoke(@PathVariable UUID requestId) {
        service.revoke(requestId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/repository-link-requests/redeem")
    ResponseEntity<Redeemed> redeem(@Valid @RequestBody RedeemRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.redeem(body.token(), body.repositoryUrl()));
    }

    record IssueRequest(Integer expiresInHours) {
    }

    record RedeemRequest(@Size(max = 100) String token, @Size(max = 400) String repositoryUrl) {
    }
}
