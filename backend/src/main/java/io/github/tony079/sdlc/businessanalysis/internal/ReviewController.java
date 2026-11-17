package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.ClientBrd;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.NotificationView;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.ReviewDetail;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.ReviewQueueEntry;
import io.github.tony079.sdlc.businessanalysis.internal.ReviewService.Note;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TDD 20.8 (BRD, review, release) and 20.9 (notifications). Staff endpoints live under /api/v1/staff (staff-only
 * session); client endpoints under the requirement (owner-only). Approver identity comes from the session only.
 */
@RestController
@Profile("!migrate & !provision")
class ReviewController {

    private final ReviewService reviews;
    private final ClientBrdService client;
    private final ReleaseService releases;
    private final OperatorRetryService retries;
    private final ChangeRequestService changeRequests;

    ReviewController(ReviewService reviews, ClientBrdService client, ReleaseService releases,
                     OperatorRetryService retries, ChangeRequestService changeRequests) {
        this.changeRequests = changeRequests;
        this.reviews = reviews;
        this.client = client;
        this.releases = releases;
        this.retries = retries;
    }

    @GetMapping("/api/v1/staff/reviews")
    List<ReviewQueueEntry> queue() {
        return reviews.queue();
    }

    @GetMapping("/api/v1/staff/brd-versions/{id}")
    ReviewDetail detail(@PathVariable UUID id) {
        return reviews.detail(id);
    }

    @GetMapping("/api/v1/staff/failed-jobs")
    List<OperatorRetryService.DeadJob> deadJobs() {
        return retries.deadJobs();
    }

    @PostMapping("/api/v1/staff/failed-jobs/{id}/retry")
    Map<String, String> retry(@PathVariable UUID id, @Valid @RequestBody RetryRequest body) {
        return retries.requestRetry(id, body.reason());
    }

    // The author retries a failed analysis (at most three times, not charged again).
    @GetMapping("/api/v1/requirements/{id}/analysis-retry")
    OperatorRetryService.AuthorRetry authorRetry(@PathVariable UUID id) {
        return retries.authorRetry(id);
    }

    @PostMapping("/api/v1/requirements/{id}/analysis-retry")
    OperatorRetryService.AuthorRetry requestAuthorRetry(@PathVariable UUID id) {
        return retries.requestAuthorRetry(id);
    }

    record RetryRequest(@jakarta.validation.constraints.Size(max = 2000) String reason) {
    }

    @GetMapping("/api/v1/staff/releases")
    List<ReleaseService.PendingRelease> pendingReleases() {
        return releases.pending();
    }

    @PostMapping("/api/v1/staff/brd-versions/{id}/release")
    ReleaseService.ReleaseView approveRelease(@PathVariable UUID id, @Valid @RequestBody ReleaseRequest body) {
        return releases.approve(id, body.expectedContentHash(), body.expectedCommitSha(), body.reason());
    }

    record ReleaseRequest(@jakarta.validation.constraints.Size(max = 64) String expectedContentHash,
                          @jakarta.validation.constraints.Size(max = 40) String expectedCommitSha,
                          @jakarta.validation.constraints.Size(max = 4000) String reason) {
    }

    @GetMapping("/api/v1/staff/brd-versions/{id}/diff")
    BrdViews.VersionDiff diff(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestParam UUID against) {
        return reviews.diff(id, against);
    }

    @PostMapping("/api/v1/staff/brd-versions/{id}/flags/{itemKey}/acknowledge")
    ResponseEntity<Void> acknowledge(@PathVariable UUID id, @PathVariable String itemKey,
                                     @Valid @RequestBody(required = false) AckRequest body) {
        reviews.acknowledge(id, itemKey, body == null ? null : body.note());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/staff/brd-versions/{id}/approve")
    Map<String, String> approve(@PathVariable UUID id, @Valid @RequestBody ApproveRequest body) {
        return Map.of("state", reviews.approve(id, body.expectedContentHash()).name());
    }

    @PostMapping("/api/v1/staff/brd-versions/{id}/request-revision")
    Map<String, String> requestRevision(@PathVariable UUID id, @Valid @RequestBody RevisionRequest body) {
        return Map.of("state", reviews.requestRevision(id, body.notes()).name());
    }

    @PostMapping("/api/v1/staff/brd-versions/{id}/reject")
    Map<String, String> reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return Map.of("state", reviews.reject(id, body.reason()).name());
    }

    @GetMapping("/api/v1/requirements/{id}/brd")
    ClientBrd released(@PathVariable UUID id) {
        return client.released(id);
    }

    /** GD-24: the approved BRD as a PDF or Word file (format=pdf|docx); tz names the reader's time zone for dates. */
    @GetMapping("/api/v1/requirements/{id}/brd/export")
    ResponseEntity<byte[]> export(@PathVariable UUID id,
                                  @org.springframework.web.bind.annotation.RequestParam(defaultValue = "pdf") String format,
                                  @org.springframework.web.bind.annotation.RequestParam(required = false) String tz) {
        boolean word = "docx".equalsIgnoreCase(format);
        if (!word && !"pdf".equalsIgnoreCase(format)) {
            throw io.github.tony079.sdlc.common.api.ApiException.validation(
                    List.of(new io.github.tony079.sdlc.common.api.ApiError.FieldError("format", "UNSUPPORTED")));
        }
        java.time.ZoneId zone = java.time.ZoneOffset.UTC;
        if (tz != null && !tz.isBlank()) {
            try {
                zone = java.time.ZoneId.of(tz);
            } catch (java.time.DateTimeException e) {
                zone = java.time.ZoneOffset.UTC;
            }
        }
        BrdDocument document = client.export(id, zone);
        byte[] bytes = word ? BrdDocxRenderer.render(document) : BrdPdfRenderer.render(document);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(document.fileStem() + (word ? ".docx" : ".pdf")).build().toString())
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(word ? org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                        : org.springframework.http.MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    // GD-24: the client's review of the BRD.
    @PostMapping("/api/v1/requirements/{id}/brd/flags/{itemKey}/accept")
    ClientBrd acceptFlag(@PathVariable UUID id, @PathVariable String itemKey) {
        return client.acceptFlag(id, itemKey);
    }

    @PostMapping("/api/v1/requirements/{id}/brd/confirm")
    ClientBrd confirm(@PathVariable UUID id) {
        return client.confirm(id);
    }

    @PostMapping("/api/v1/requirements/{id}/brd/approve")
    ClientBrd approveBrd(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        return client.approve(id, body == null ? null : body.get("expectedContentHash"));
    }

    record RevisionText(@Size(max = 5000) String text, Boolean acceptCharge) {
    }

    @PostMapping("/api/v1/requirements/{id}/brd/request-changes")
    ClientBrd requestRevision(@PathVariable UUID id, @RequestBody RevisionText body) {
        return client.requestRevision(id, body.text(), Boolean.TRUE.equals(body.acceptCharge()));
    }

    @PostMapping("/api/v1/requirements/{id}/client-changes")
    Map<String, String> clientChanges(@PathVariable UUID id, @Valid @RequestBody ChangeRequest body) {
        return Map.of("changeRequestId", client.requestChanges(id, body.text(), changeRequests).toString(),
                "status", "SUBMITTED");
    }

    @GetMapping("/api/v1/notifications")
    List<NotificationView> notifications() {
        return client.notifications();
    }

    @PostMapping("/api/v1/notifications/{id}/read")
    ResponseEntity<Void> read(@PathVariable UUID id) {
        client.markRead(id);
        return ResponseEntity.noContent().build();
    }

    record AckRequest(@Size(max = 1000) String note) {
    }

    record ApproveRequest(@NotBlank @Size(max = 64) String expectedContentHash) {
    }

    record RevisionRequest(@Size(max = 50) List<Note> notes) {
    }

    record ReasonRequest(@Size(max = 2000) String reason) {
    }

    record ChangeRequest(@Size(max = 5000) String text) {
    }
}
