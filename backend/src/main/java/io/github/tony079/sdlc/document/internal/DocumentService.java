package io.github.tony079.sdlc.document.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.document.api.DocumentView.Item;
import io.github.tony079.sdlc.document.api.DocumentView.ItemList;
import io.github.tony079.sdlc.document.api.DocumentView.Limits;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.requirement.api.RequirementDirectory;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;
import io.github.tony079.sdlc.storage.api.ObjectStore;
import io.github.tony079.sdlc.storage.api.ObjectStore.StoredObject;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachment upload, listing, download and removal (PRD 7A.1, TDD 15.2). Visibility follows the requirement:
 * owner-only (C-18 open); another tenant's or user's requirement or document is a 404 and a security event.
 *
 * <p>Upload: cheap checks first (name, type, declared MIME, size, limits), then the content is streamed into private
 * encrypted storage (never fully in memory), then one transaction re-checks limits and duplicates under the
 * workflow lock, records the version as QUARANTINED and writes DocumentUploaded to the outbox. Validation of the real
 * content and the malware scan run in the worker; nothing is analysed or downloadable before a clean scan.
 */
@Profile("!migrate & !provision")
@Service
class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    /** Non-terminal states reachable so far in which attachments may be added (PRD 0.4: also MANUAL_INTAKE). */
    /**
     * PRD AC-DOC-14: a document may also be added after analysis started; the engine then marks the running analysis
     * or the BRD version in review stale (businessanalysis InputChangeConsumer).
     */
    static final Set<WorkflowState> UPLOADABLE = Set.of(WorkflowState.DRAFT, WorkflowState.SUBMITTED,
            WorkflowState.MANUAL_INTAKE, WorkflowState.REPO_VALIDATING, WorkflowState.AWAITING_DOCUMENTS,
            WorkflowState.AWAITING_DOCUMENT_DECISION, WorkflowState.ANALYSING, WorkflowState.AWAITING_CLARIFICATION,
            WorkflowState.INTERNAL_REVIEW);

    private final DocumentRepository documents;
    private final RequirementDirectory requirements;
    private final WorkflowEngine workflow;
    private final ObjectStore storage;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final SecurityEventRecorder securityEvents;
    private final TransactionTemplate tx;
    private final Limits limits;

    DocumentService(DocumentRepository documents, RequirementDirectory requirements, WorkflowEngine workflow,
                    ObjectStore storage, OutboxPublisher outbox, AuditWriter audit, SecurityEventRecorder securityEvents,
                    PlatformTransactionManager transactionManager,
                    @Value("${sdlc.documents.max-file-bytes:20971520}") long maxFileBytes,
                    @Value("${sdlc.documents.max-files:10}") int maxFiles,
                    @Value("${sdlc.documents.max-total-bytes:104857600}") long maxTotalBytes) {
        this.documents = documents;
        this.requirements = requirements;
        this.workflow = workflow;
        this.storage = storage;
        this.outbox = outbox;
        this.audit = audit;
        this.securityEvents = securityEvents;
        this.tx = new TransactionTemplate(transactionManager);
        this.limits = new Limits(maxFileBytes, maxFiles, maxTotalBytes);
    }

    Limits limits() {
        return limits;
    }

    Item upload(UUID requirementId, MultipartFile file) {
        UUID tenant = TenantContext.requireTenant();
        UUID actor = actor();
        String fileName = cleanFileName(file.getOriginalFilename());
        DocumentType type = DocumentType.byFileName(fileName).orElseThrow(() -> invalid("UNSUPPORTED_FILE_TYPE"));
        if (!type.acceptsDeclaredMime(file.getContentType())) {
            throw invalid("CONTENT_TYPE_MISMATCH");
        }
        if (file.isEmpty()) {
            throw invalid("EMPTY_FILE");
        }
        if (file.getSize() > limits.maxFileBytes()) {
            throw tooLarge();
        }
        tx.executeWithoutResult(status -> {
            ownedOrNotFound(requirementId);
            checkLimits(requirementId, file.getSize());
        });

        StoredObject stored;
        try (InputStream in = file.getInputStream()) {
            stored = storage.put(tenant, in, limits.maxFileBytes());
        } catch (ObjectStore.TooLargeException e) {
            throw tooLarge();
        } catch (IOException e) {
            log.warn("Attachment could not be stored", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_FAILED", "The file could not be stored. Try again.");
        }

        try {
            return tx.execute(status -> {
                ownedOrNotFound(requirementId);
                WorkflowState state = workflow.lockState(requirementId);
                if (!UPLOADABLE.contains(state)) {
                    throw ApiException.conflict("DOCUMENTS_LOCKED", "Attachments cannot be added in state " + state + ".");
                }
                checkLimits(requirementId, stored.size());
                Optional<UUID> duplicate = documents.liveDuplicate(requirementId, stored.sha256());
                if (duplicate.isPresent()) {
                    throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_DOCUMENT",
                            "This file is already attached to the requirement.",
                            List.of(new FieldError("file", "DUPLICATE_OF:" + duplicate.get())));
                }
                UUID documentId = UUID.randomUUID();
                UUID versionId = UUID.randomUUID();
                documents.insertDocument(documentId, tenant, requirementId, actor);
                documents.insertVersion(versionId, tenant, documentId, requirementId, fileName,
                        truncate(file.getContentType()), type, stored.size(), stored.sha256(), stored.key(), actor);
                outbox.publish("DOCUMENT", documentId, EventTypes.DOCUMENT_UPLOADED, Map.of(
                        "documentId", documentId.toString(), "versionId", versionId.toString(),
                        "requirementId", requirementId.toString()));
                audit.record(AuditAction.DOCUMENT_UPLOADED, "DOCUMENT", documentId, AuditResult.SUCCESS);
                return documents.list(requirementId).stream().filter(i -> i.documentId().equals(documentId))
                        .findFirst().orElseThrow();
            });
        } catch (RuntimeException e) {
            deleteQuietly(stored.key());
            if (e instanceof DuplicateKeyException) {
                throw ApiException.conflict("DUPLICATE_DOCUMENT", "This file is already attached to the requirement.");
            }
            throw e;
        }
    }

    ItemList list(UUID requirementId) {
        return tx.execute(status -> {
            ownedOrNotFound(requirementId);
            return new ItemList(documents.list(requirementId), limits);
        });
    }

    record Download(String fileName, InputStream content) {
    }

    /** Content of the current version, only after a clean scan (PRD FR-UPL-11, FR-STO-06). Audited. */
    Download download(UUID requirementId, UUID documentId) {
        DocumentRepository.Current current = tx.execute(status -> {
            ownedOrNotFound(requirementId);
            DocumentRepository.Current found = documents.current(requirementId, documentId)
                    .orElseThrow(() -> notFound("DOCUMENT:" + documentId));
            if (!DocumentRepository.DOWNLOADABLE.contains(found.state())) {
                throw ApiException.conflict("DOCUMENT_NOT_AVAILABLE",
                        "The file is not available until it has passed validation and the malware scan.");
            }
            audit.record(AuditAction.DOCUMENT_DOWNLOADED, "DOCUMENT", documentId, AuditResult.SUCCESS);
            return found;
        });
        try {
            return new Download(current.fileName(), storage.open(current.storageKey()));
        } catch (IOException e) {
            log.error("Stored attachment {} could not be read", documentId, e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "The file cannot be read now.");
        }
    }

    /** Removal before submission (FR-UPL-09). After submission, changes need document versioning (later milestone). */
    void remove(UUID requirementId, UUID documentId) {
        tx.executeWithoutResult(status -> {
            ownedOrNotFound(requirementId);
            WorkflowState state = workflow.lockState(requirementId);
            if (state != WorkflowState.DRAFT) {
                throw ApiException.conflict("DOCUMENTS_LOCKED",
                        "Attachments can only be removed before the requirement is submitted.");
            }
            documents.current(requirementId, documentId).orElseThrow(() -> notFound("DOCUMENT:" + documentId));
            documents.remove(documentId, actor());
            audit.record(AuditAction.DOCUMENT_REMOVED, "DOCUMENT", documentId, AuditResult.SUCCESS);
        });
    }

    private void checkLimits(UUID requirementId, long incoming) {
        DocumentRepository.Usage usage = documents.usage(requirementId);
        if (usage.files() >= limits.maxFiles()) {
            throw invalid("TOO_MANY_FILES");
        }
        if (usage.bytes() + incoming > limits.maxTotalBytes()) {
            throw invalid("TOTAL_SIZE_EXCEEDED");
        }
    }

    private void ownedOrNotFound(UUID requirementId) {
        requirements.findOwned(requirementId).orElseThrow(() -> notFound("REQUIREMENT:" + requirementId));
    }

    private ApiException notFound(String target) {
        securityEvents.record(SecurityEventType.RESOURCE_NOT_FOUND, Severity.LOW, actor().toString(), target);
        return ApiException.notFound();
    }

    /** Keeps only the final path segment of the client's file name and refuses control characters. */
    static String cleanFileName(String original) {
        if (original == null) {
            throw invalid("INVALID_FILE_NAME");
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 255
                || name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw invalid("INVALID_FILE_NAME");
        }
        return name;
    }

    private static ApiException invalid(String code) {
        return ApiException.validation(List.of(new FieldError("file", code)));
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                "The file exceeds the limit of " + limits.maxFileBytes() / (1024 * 1024) + " MB.");
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Could not delete unreferenced stored object", e);
        }
    }

    private static String truncate(String value) {
        return value == null ? null : value.length() > 255 ? value.substring(0, 255) : value;
    }

    private static UUID actor() {
        return TenantContext.currentActor().orElseThrow(() -> new IllegalStateException("No authenticated actor"));
    }
}
