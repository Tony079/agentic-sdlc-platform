package io.github.tony079.sdlc.businessanalysis.internal;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.EstimateService.EstimateState;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GD-33: the Estimation stage of a requirement. Identities come from the session only. */
@RestController
@Profile("!migrate & !provision")
class EstimateController {

    private static final String BASE = "/api/v1/requirements/{id}/estimate";

    private final EstimateService estimates;

    EstimateController(EstimateService estimates) {
        this.estimates = estimates;
    }

    @GetMapping(BASE)
    EstimateState state(@PathVariable UUID id) {
        return estimates.state(id);
    }

    record StartBody(Map<String, Object> settings) {
    }

    @PostMapping(BASE + "/start")
    EstimateState start(@PathVariable UUID id, @RequestBody(required = false) StartBody body) {
        return estimates.start(id, body == null ? null : body.settings());
    }

    @PostMapping(BASE + "/retry")
    EstimateState retry(@PathVariable UUID id) {
        return estimates.retry(id);
    }

    @PostMapping(BASE + "/flags/{itemKey}/accept")
    EstimateState acceptFlag(@PathVariable UUID id, @PathVariable String itemKey) {
        return estimates.acceptFlag(id, itemKey);
    }

    @PostMapping(BASE + "/gaps/{designKey}/accept")
    EstimateState acceptGap(@PathVariable UUID id, @PathVariable String designKey) {
        return estimates.acceptGap(id, designKey);
    }

    record Adjustment(Double low, Double likely, Double high, @Size(max = 1000) String reason) {
    }

    @PostMapping(BASE + "/items/{itemKey}/adjust")
    EstimateState adjust(@PathVariable UUID id, @PathVariable String itemKey, @RequestBody Adjustment body) {
        return estimates.adjust(id, itemKey, body.low(), body.likely(), body.high(), body.reason());
    }

    @PostMapping(BASE + "/approve")
    EstimateState approve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        return estimates.approve(id, body == null ? null : (String) body.get("expectedReviewHash"),
                body != null && Boolean.TRUE.equals(body.get("confirmApproveAlone")));
    }

    record ChangeText(@Size(max = 5000) String text, Map<String, Object> settings) {
    }

    @PostMapping(BASE + "/request-changes")
    EstimateState requestChanges(@PathVariable UUID id, @RequestBody ChangeText body) {
        return estimates.requestChanges(id, body.text(), body.settings());
    }

    @GetMapping(BASE + "/export")
    ResponseEntity<byte[]> export(@PathVariable UUID id, @RequestParam(defaultValue = "pdf") String format,
                                  @RequestParam(required = false) String tz,
                                  @RequestParam(required = false) Integer version) {
        String f = format.toLowerCase(java.util.Locale.ROOT);
        if (!List.of("pdf", "docx", "csv").contains(f)) {
            throw ApiException.validation(List.of(new FieldError("format", "UNSUPPORTED")));
        }
        ZoneId zone = ZoneOffset.UTC;
        if (tz != null && !tz.isBlank()) {
            try {
                zone = ZoneId.of(tz);
            } catch (DateTimeException e) {
                zone = ZoneOffset.UTC;
            }
        }
        EstimateService.VersionV view = estimates.approvedVersion(id, version);
        BrdDocument document = estimates.document(id, view, zone);
        byte[] bytes;
        MediaType type;
        if ("csv".equals(f)) {
            // A byte-order mark so spreadsheet programs read the text as UTF-8.
            bytes = ("﻿" + EstimateService.csv(view)).getBytes(StandardCharsets.UTF_8);
            type = MediaType.parseMediaType("text/csv; charset=UTF-8");
        } else if ("docx".equals(f)) {
            bytes = BrdDocxRenderer.render(document);
            type = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        } else {
            bytes = BrdPdfRenderer.render(document);
            type = MediaType.APPLICATION_PDF;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.fileStem() + "." + f).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(type)
                .body(bytes);
    }
}
