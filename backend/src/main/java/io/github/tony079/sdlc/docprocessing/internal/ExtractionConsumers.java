package io.github.tony079.sdlc.docprocessing.internal;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.document.api.DocumentProcessing;
import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Outbox consumers that start and conclude document processing. Delivered only in the worker. */
@Configuration(proxyBeanMethods = false)
class ExtractionConsumers {

    private static final Logger log = LoggerFactory.getLogger(ExtractionConsumers.class);

    /**
     * On DocumentScanned (clean), enqueues one DOCUMENT_EXTRACT job per version, unless:
     * <ul>
     *   <li>extraction is switched off ({@code sdlc.documents.extraction.enabled=false}, the default): the isolated
     *       extractor has not passed the O-15 spike, so real client documents must not be parsed (secure gate);</li>
     *   <li>the requirement is in MANUAL_INTAKE: no extraction is scheduled there (PRD 0.4, TDD 15.4, WF-M01); the
     *       resume transition T04 schedules it later (gated by O-06).</li>
     * </ul>
     */
    @Bean
    EventConsumer extractionEnqueueConsumer(JobQueue jobs, WorkflowEngine workflow,
                                            @Value("${sdlc.documents.extraction.enabled:false}") boolean enabled) {
        return new EventConsumer() {
            @Override
            public String name() {
                return "document-extract-enqueue";
            }

            @Override
            public boolean accepts(String eventType) {
                return EventTypes.DOCUMENT_SCANNED.equals(eventType);
            }

            @Override
            public void handle(DomainEvent event) {
                UUID version = event.uuid("versionId");
                if (!enabled) {
                    log.info("Extraction gated (O-15): version {} stays READY_FOR_EXTRACTION", version);
                    return;
                }
                if (workflow.state(event.uuid("requirementId")) == WorkflowState.MANUAL_INTAKE) {
                    return;
                }
                jobs.enqueue(new JobRequest(ExtractionHandler.JOB_TYPE, "extract:" + version, "DOCUMENT",
                        event.uuid("documentId"), null, Map.of("versionId", version.toString()), 60));
            }
        };
    }

    /**
     * AC-EXT-42: after the Business Reviewer resumes a manual-intake requirement (T04), extraction is scheduled for its
     * stored, scanned documents (READY_FOR_EXTRACTION), still behind the O-15 gate.
     */
    @Bean
    EventConsumer extractionResumeConsumer(JobQueue jobs, DocumentProcessing documents,
                                           @Value("${sdlc.documents.extraction.enabled:false}") boolean enabled) {
        return new EventConsumer() {
            @Override
            public String name() {
                return "document-extract-resume";
            }

            @Override
            public boolean accepts(String eventType) {
                return EventTypes.MANUAL_INTAKE_RESUMED.equals(eventType);
            }

            @Override
            public void handle(DomainEvent event) {
                if (!enabled) {
                    return;
                }
                for (var document : documents.analysisDocuments(event.uuid("requirementId"))) {
                    if ("READY_FOR_EXTRACTION".equals(document.state())) {
                        jobs.enqueue(new JobRequest(ExtractionHandler.JOB_TYPE, "extract:" + document.versionId(),
                                "DOCUMENT", document.documentId(), null,
                                Map.of("versionId", document.versionId().toString()), 60));
                    }
                }
            }
        };
    }

    /** A DOCUMENT_EXTRACT job that used up its three attempts ends the version in EXTRACTION_FAILED_FINAL. */
    @Bean
    EventConsumer extractionDeadConsumer(DocumentProcessing documents) {
        return new EventConsumer() {
            @Override
            public String name() {
                return "document-extract-dead";
            }

            @Override
            public boolean accepts(String eventType) {
                return EventTypes.JOB_DEAD.equals(eventType);
            }

            @Override
            public void handle(DomainEvent event) {
                if (!ExtractionHandler.JOB_TYPE.equals(event.payload().get("jobType"))) {
                    return;
                }
                documents.currentVersion(event.aggregateId()).ifPresent(version ->
                        documents.recordOutcome(version, "EXTRACTION_FAILED_FINAL", "EXTRACTION_RETRIES_EXHAUSTED"));
            }
        };
    }
}
