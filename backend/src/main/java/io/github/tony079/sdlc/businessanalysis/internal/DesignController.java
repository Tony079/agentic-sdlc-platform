package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.DesignService.DesignState;
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

/** GD-26: the Technical design stage of a requirement. Identities come from the session only. */
@RestController
@Profile("!migrate & !provision")
class DesignController {

    private static final String BASE = "/api/v1/requirements/{id}/design";

    private final DesignService designs;

    DesignController(DesignService designs) {
        this.designs = designs;
    }

    @GetMapping(BASE)
    DesignState state(@PathVariable UUID id) {
        return designs.state(id);
    }

    @PostMapping(BASE + "/start")
    DesignState start(@PathVariable UUID id) {
        return designs.start(id);
    }

    @PostMapping(BASE + "/retry")
    DesignState retry(@PathVariable UUID id) {
        return designs.retry(id);
    }

    @PostMapping(BASE + "/flags/{itemKey}/accept")
    DesignState acceptFlag(@PathVariable UUID id, @PathVariable String itemKey) {
        return designs.acceptFlag(id, itemKey);
    }

    @PostMapping(BASE + "/confirm")
    DesignState confirm(@PathVariable UUID id) {
        return designs.confirm(id);
    }

    @PostMapping(BASE + "/approve")
    DesignState approve(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        return designs.approve(id, body == null ? null : body.get("expectedContentHash"));
    }

    record ChangeText(@Size(max = 5000) String text) {
    }

    @PostMapping(BASE + "/request-changes")
    DesignState requestChanges(@PathVariable UUID id, @RequestBody ChangeText body) {
        return designs.requestChanges(id, body.text());
    }

    @GetMapping(BASE + "/export")
    ResponseEntity<byte[]> export(@PathVariable UUID id, @RequestParam(defaultValue = "pdf") String format,
                                  @RequestParam(required = false) String tz,
                                  @RequestParam(required = false) Integer version) {
        boolean word = "docx".equalsIgnoreCase(format);
        if (!word && !"pdf".equalsIgnoreCase(format)) {
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
        BrdDocument document = designs.export(id, version, zone);
        byte[] bytes = word ? BrdDocxRenderer.render(document) : BrdPdfRenderer.render(document);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.fileStem() + (word ? ".docx" : ".pdf")).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(word ? MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                        : MediaType.APPLICATION_PDF)
                .body(bytes);
    }
}
