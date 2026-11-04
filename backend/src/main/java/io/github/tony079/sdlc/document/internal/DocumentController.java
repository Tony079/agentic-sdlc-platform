package io.github.tony079.sdlc.document.internal;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import io.github.tony079.sdlc.document.api.DocumentView.Item;
import io.github.tony079.sdlc.document.api.DocumentView.ItemList;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Requirement attachments (PRD 7A.1). The tenant and user come only from the session. */
@Profile("!migrate")
@RestController
@RequestMapping("/api/v1/requirements/{requirementId}/documents")
class DocumentController {

    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    ItemList list(@PathVariable UUID requirementId) {
        return documents.list(requirementId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Item> upload(@PathVariable UUID requirementId, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documents.upload(requirementId, file));
    }

    /** Download as an attachment, never rendered inline; content type is not trusted from the upload. */
    @GetMapping("/{documentId}/content")
    ResponseEntity<StreamingResponseBody> download(@PathVariable UUID requirementId, @PathVariable UUID documentId) {
        DocumentService.Download download = documents.download(requirementId, documentId);
        StreamingResponseBody body = out -> {
            try (InputStream in = download.content()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    @DeleteMapping("/{documentId}")
    ResponseEntity<Void> remove(@PathVariable UUID requirementId, @PathVariable UUID documentId) {
        documents.remove(requirementId, documentId);
        return ResponseEntity.noContent().build();
    }
}
