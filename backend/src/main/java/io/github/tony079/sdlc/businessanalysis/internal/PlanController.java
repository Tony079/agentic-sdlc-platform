package io.github.tony079.sdlc.businessanalysis.internal;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.PlanService.PlanState;
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

/** GD-34: the Delivery plan stage of a requirement. Identities come from the session only. */
@RestController
@Profile("!migrate & !provision")
class PlanController {

    private static final String BASE = "/api/v1/requirements/{id}/plan";

    private final PlanService plans;

    PlanController(PlanService plans) {
        this.plans = plans;
    }

    @GetMapping(BASE)
    PlanState state(@PathVariable UUID id) {
        return plans.state(id);
    }

    record StartBody(Map<String, Object> settings) {
    }

    @PostMapping(BASE + "/start")
    PlanState start(@PathVariable UUID id, @RequestBody(required = false) StartBody body) {
        return plans.start(id, body == null ? null : body.settings());
    }

    @PostMapping(BASE + "/retry")
    PlanState retry(@PathVariable UUID id) {
        return plans.retry(id);
    }

    @PostMapping(BASE + "/flags/{storyKey}/accept")
    PlanState acceptFlag(@PathVariable UUID id, @PathVariable String storyKey) {
        return plans.acceptFlag(id, storyKey);
    }

    @PostMapping(BASE + "/gaps/{key}/accept")
    PlanState acceptGap(@PathVariable UUID id, @PathVariable String key) {
        return plans.acceptGap(id, key);
    }

    record Edit(Map<String, Object> changes, @Size(max = 1000) String reason) {
    }

    @PostMapping(BASE + "/stories/{storyKey}/edit")
    PlanState edit(@PathVariable UUID id, @PathVariable String storyKey, @RequestBody Edit body) {
        return plans.edit(id, storyKey, body.changes(), body.reason());
    }

    @PostMapping(BASE + "/approve")
    PlanState approve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        return plans.approve(id, body == null ? null : (String) body.get("expectedReviewHash"),
                body != null && Boolean.TRUE.equals(body.get("confirmApproveAlone")));
    }

    record ChangeText(@Size(max = 5000) String text) {
    }

    @PostMapping(BASE + "/request-changes")
    PlanState requestChanges(@PathVariable UUID id, @RequestBody ChangeText body) {
        return plans.requestChanges(id, body.text());
    }

    record FlagBody(String flag) {
    }

    @PostMapping(BASE + "/flags/accept-all")
    PlanState acceptAllFlags(@PathVariable UUID id, @RequestBody FlagBody body) {
        return plans.acceptAllFlags(id, body == null ? null : body.flag());
    }

    @PostMapping(BASE + "/settings")
    PlanState changeSettings(@PathVariable UUID id, @RequestBody StartBody body) {
        return plans.changeSettings(id, body == null ? null : body.settings());
    }

    record PushBody(UUID repositoryId, String baseBranch) {
    }

    /** GD-34 phase 2: push the approved plan to GitHub Issues of a verified project repository. */
    @PostMapping(BASE + "/push")
    PlanState push(@PathVariable UUID id, @RequestBody PushBody body) {
        return plans.push(id, body == null ? null : body.repositoryId(), body == null ? null : body.baseBranch());
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
        PlanService.VersionV view = plans.approvedVersion(id, version);
        BrdDocument document = plans.document(id, view, zone);
        byte[] bytes;
        MediaType type;
        if ("csv".equals(f)) {
            bytes = ("﻿" + PlanService.csv(view)).getBytes(StandardCharsets.UTF_8);
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
