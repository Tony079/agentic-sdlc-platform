package io.github.tony079.sdlc.document.internal;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;

import org.springframework.stereotype.Component;

/**
 * On DocumentUploaded, enqueues exactly one DOCUMENT_SCAN job per version (idempotency key {@code scan:<version>}).
 * Runs in the worker: the API login has no access to the job queue.
 */
@Component
class DocumentUploadedConsumer implements EventConsumer {

    private final JobQueue jobs;

    DocumentUploadedConsumer(JobQueue jobs) {
        this.jobs = jobs;
    }

    @Override
    public String name() {
        return "document-scan-enqueue";
    }

    @Override
    public boolean accepts(String eventType) {
        return EventTypes.DOCUMENT_UPLOADED.equals(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        UUID document = event.uuid("documentId");
        UUID version = event.uuid("versionId");
        jobs.enqueue(new JobRequest(DocumentScanHandler.JOB_TYPE, "scan:" + version, "DOCUMENT", document, null,
                Map.of("versionId", version.toString()), 50));
    }
}
