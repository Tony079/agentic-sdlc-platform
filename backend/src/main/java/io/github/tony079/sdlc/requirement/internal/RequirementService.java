package io.github.tony079.sdlc.requirement.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.project.api.ProjectAccess;
import io.github.tony079.sdlc.project.api.ProjectRole;
import io.github.tony079.sdlc.requirement.api.RequirementView.Detail;
import io.github.tony079.sdlc.requirement.api.RequirementView.Revision;
import io.github.tony079.sdlc.requirement.api.RequirementView.Summary;
import io.github.tony079.sdlc.requirement.api.RequirementView.SummaryPage;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.Route;
import io.github.tony079.sdlc.workflow.api.TimelineEntry;
import io.github.tony079.sdlc.workflow.api.Transition;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requirement creation and management (PRD FR-REQ-01/03/04/06). Visibility is owner-only for both client roles
 * (PRD section 4; decision Q3 / conflict C-18 remains open): a requirement the caller did not create is answered
 * with 404 exactly like another tenant's, and the miss is recorded as a security event.
 *
 * <p>Every change runs in one transaction: optimistic version check, new immutable revision or workflow
 * transition, and audit event commit together or not at all.
 */
@Service
class RequirementService {

    static final String CREATE_OPERATION = "REQUIREMENT_CREATE";
    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 100;
    static final int REASON_MIN = 10;
    static final int REASON_MAX = 500;

    private final RequirementRepository requirements;
    private final RequirementKeyGenerator keys;
    private final IdempotencyStore idempotency;
    private final WorkflowEngine workflow;
    private final AuditWriter audit;
    private final SecurityEventRecorder securityEvents;
    private final ProjectAccess projects;

    RequirementService(RequirementRepository requirements, RequirementKeyGenerator keys, IdempotencyStore idempotency,
                       WorkflowEngine workflow, AuditWriter audit, SecurityEventRecorder securityEvents,
                       ProjectAccess projects) {
        this.projects = projects;
        this.requirements = requirements;
        this.keys = keys;
        this.idempotency = idempotency;
        this.workflow = workflow;
        this.audit = audit;
        this.securityEvents = securityEvents;
    }

    record CreateResult(Detail detail, boolean replayed) {
    }

    @Transactional
    public CreateResult create(UUID projectId, RequirementFields fields, UUID idempotencyKey) {
        UUID tenant = TenantContext.requireTenant();
        UUID actor = actor();

        List<FieldError> errors = new ArrayList<>();
        if (projectId == null) {
            errors.add(new FieldError("projectId", "REQUIRED"));
        }
        errors.addAll(fields.draftErrors());
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        if (!requirements.projectExists(projectId)) {
            // Unknown, other-tenant and invisible projects are indistinguishable under RLS.
            throw ApiException.validation(List.of(new FieldError("projectId", "INVALID_VALUE")));
        }
        // FR-ACC-01: creating a requirement needs the REQUESTER or APPROVER role in an active project (GD-02).
        if (!projects.roleOf(projectId).map(ProjectRole::canRequest).orElse(false)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ROLE_REQUIRED",
                    "You need the Requester or Approver role in this project to create requirements.");
        }
        if (!projects.isActive(projectId)) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "Requirements cannot be created in an archived project.");
        }

        UUID id = UUID.randomUUID();
        if (idempotencyKey != null) {
            String requestHash = RequirementFields.sha256(
                    RequirementFields.canonical(projectId.toString(), fields.contentHash()));
            if (!idempotency.claim(tenant, actor, CREATE_OPERATION, idempotencyKey, requestHash, id)) {
                // Separate statement: sees the committed winner (V-M2-5).
                IdempotencyStore.Entry existing = idempotency.find(actor, CREATE_OPERATION, idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("Idempotency record not visible after conflict"));
                if (!existing.requestHash().equals(requestHash)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                            "This Idempotency-Key was already used with a different request.");
                }
                return new CreateResult(detail(existing.resourceId(), actor), true);
            }
        }

        String key = keys.next(tenant);
        requirements.insertRequirement(id, tenant, projectId, key, actor);
        requirements.insertRevision(tenant, id, 1, fields, actor);
        workflow.start(id);
        audit.record(AuditAction.REQUIREMENT_CREATED, "REQUIREMENT", id, AuditResult.SUCCESS);
        return new CreateResult(detail(id, actor), false);
    }

    @Transactional(readOnly = true)
    public Detail get(UUID id) {
        return detail(id, actor());
    }

    /**
     * GD-05 / FR-ACC-02: the owner or an Approver of the project marks a requirement restricted (visible only to its
     * owner and the project's Approvers) or lifts the restriction.
     */
    @Transactional
    public Detail setRestricted(UUID id, boolean restricted) {
        RequirementRepository.Row row = visible(id);
        boolean approver = projects.roleOf(row.projectId()).map(ProjectRole::canApprove).orElse(false);
        if (!row.createdBy().equals(actor()) && !approver) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_OR_APPROVER_REQUIRED",
                    "Only the owner or an Approver of the project can change who sees this requirement.");
        }
        if (row.restricted() != restricted) {
            requirements.setRestricted(id, restricted);
            audit.record(AuditAction.REQUIREMENT_RESTRICTION_CHANGED, "REQUIREMENT", id, AuditResult.SUCCESS);
        }
        return detail(id, actor());
    }

    @Transactional(readOnly = true)
    public SummaryPage list(WorkflowState state, UUID projectId, String cursor, Integer limit, boolean mine) {
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw ApiException.validation(List.of(new FieldError("limit", "INVALID_VALUE")));
        }
        // GD-05: every requirement the caller may see in their projects; "mine" narrows to their own.
        List<Summary> rows = requirements.listVisible(mine ? actor() : null, state, projectId, decodeCursor(cursor),
                pageSize + 1);
        if (rows.size() <= pageSize) {
            return new SummaryPage(rows, null);
        }
        List<Summary> page = rows.subList(0, pageSize);
        Summary last = page.get(pageSize - 1);
        return new SummaryPage(List.copyOf(page), encodeCursor(last.updatedAt(), last.id()));
    }

    @Transactional(readOnly = true)
    public Revision revision(UUID id, int revisionNo) {
        visible(id);
        return requirements.findRevision(id, revisionNo).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID id) {
        visible(id);
        return workflow.timeline(id);
    }

    /**
     * Edits create a new immutable revision (FR-REQ-04). Allowed in DRAFT and SUBMITTED; once routed (MANUAL_INTAKE,
     * REPO_VALIDATING, ...) a requirement is not editable (interim policy, decision Q-M3-5). After submission the
     * kind cannot change (409 KIND_CHANGE_NOT_ALLOWED), because the route was already derived from it.
     *
     * <p>Lock order (all commands): workflow instance first, then the requirement row, so an edit can never
     * interleave with routing or deadlock with a concurrent submit or cancel.
     */
    @Transactional
    public Detail update(UUID id, long expectedVersion, RequirementFields fields) {
        RequirementRepository.Row row = owned(id);
        List<FieldError> errors = fields.draftErrors();
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        WorkflowState state = workflow.lockState(id);
        if (state != WorkflowState.DRAFT && state != WorkflowState.SUBMITTED) {
            throw ApiException.conflict("REQUIREMENT_NOT_EDITABLE",
                    "A requirement in state " + state + " can no longer be edited.");
        }
        if (state == WorkflowState.SUBMITTED) {
            String submittedKind = requirements.findRevision(id, row.currentRevisionNo()).orElseThrow().content().kind();
            if (!submittedKind.equals(fields.kind())) {
                throw ApiException.conflict("KIND_CHANGE_NOT_ALLOWED",
                        "The requirement type cannot be changed after submission.");
            }
        }
        int nextRevision = row.currentRevisionNo() + 1;
        if (row.version() != expectedVersion || !requirements.advance(id, expectedVersion, nextRevision)) {
            throw ApiException.versionChanged();
        }
        requirements.insertRevision(TenantContext.requireTenant(), id, nextRevision, fields, actor());
        audit.record(AuditAction.REQUIREMENT_REVISED, "REQUIREMENT", id, AuditResult.SUCCESS);
        return detail(id, actor());
    }

    /** T01 DRAFT to SUBMITTED. Completeness of the current revision is checked first (decision Q2). */
    @Transactional
    public Detail submit(UUID id, long expectedVersion) {
        RequirementRepository.Row row = owned(id);
        workflow.lockState(id);
        if (row.version() != expectedVersion) {
            throw ApiException.versionChanged();
        }
        Revision current = requirements.findRevision(id, row.currentRevisionNo()).orElseThrow();
        List<FieldError> errors = RequirementFields.of(current.content()).submitErrors();
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        if (!requirements.advance(id, expectedVersion, row.currentRevisionNo())) {
            throw ApiException.versionChanged();
        }
        // RequirementSubmitted is written to the outbox in this transaction; routing follows in the worker.
        Route route = RequirementFields.EXISTING_REPOSITORY.equals(current.content().kind()) ? Route.REPOSITORY
                : Route.MANUAL_INTAKE;
        workflow.submit(id, route);
        audit.record(AuditAction.REQUIREMENT_SUBMITTED, "REQUIREMENT", id, AuditResult.SUCCESS);
        return detail(id, actor());
    }

    /** T29 to CANCELLED, by the owner, with a reason. Nothing is deleted. */
    @Transactional
    public Detail cancel(UUID id, long expectedVersion, String reason) {
        String trimmed = reason == null ? null : reason.strip();
        if (trimmed == null || trimmed.isEmpty()) {
            throw ApiException.validation(List.of(new FieldError("reason", "REQUIRED")));
        }
        if (trimmed.length() < REASON_MIN || trimmed.length() > REASON_MAX) {
            throw ApiException.validation(List.of(new FieldError("reason", "INVALID_LENGTH")));
        }
        RequirementRepository.Row row = owned(id);
        workflow.lockState(id);
        if (row.version() != expectedVersion || !requirements.advance(id, expectedVersion, row.currentRevisionNo())) {
            throw ApiException.versionChanged();
        }
        workflow.transition(id, Transition.T29_CANCEL, trimmed);
        audit.record(AuditAction.REQUIREMENT_CANCELLED, "REQUIREMENT", id, AuditResult.SUCCESS);
        return detail(id, actor());
    }

    private Detail detail(UUID id, UUID me) {
        RequirementRepository.Row row = requirements.findVisible(id).orElseThrow(() -> notFound(id));
        Revision current = requirements.findRevision(id, row.currentRevisionNo()).orElseThrow();
        return new Detail(row.id(), row.key(), row.projectId(), workflow.state(id), row.version(),
                row.currentRevisionNo(), current.content(), requirements.revisions(id), row.createdAt(),
                row.updatedAt(), row.restricted(), row.createdBy().equals(me), projects.roleOf(row.projectId()).orElse(null));
    }

    /** A requirement the caller may see (project visibility, GD-05). */
    private RequirementRepository.Row visible(UUID id) {
        return requirements.findVisible(id).orElseThrow(() -> notFound(id));
    }

    /**
     * A requirement the caller owns: only the owner edits, submits and cancels it. A visible requirement owned by
     * someone else is refused with 403 (the caller may see it); an invisible one is 404.
     */
    private RequirementRepository.Row owned(UUID id) {
        RequirementRepository.Row row = visible(id);
        if (!row.createdBy().equals(actor())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_REQUIRED",
                    "Only the person who created this requirement can change it.");
        }
        return row;
    }

    /**
     * Not found: the id does not exist, belongs to another tenant (RLS) or to another user of this tenant
     * (owner-only visibility). All three look the same to the caller and are recorded (PRD FR-AUTH-04/13).
     */
    private ApiException notFound(UUID id) {
        securityEvents.record(SecurityEventType.RESOURCE_NOT_FOUND, Severity.LOW, actor().toString(),
                "REQUIREMENT:" + id);
        return ApiException.notFound();
    }

    private static UUID actor() {
        return TenantContext.currentActor().orElseThrow(() -> new IllegalStateException("No authenticated actor"));
    }

    static String encodeCursor(OffsetDateTime updatedAt, UUID id) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, updatedAt.toInstant());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((micros + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    static RequirementRepository.Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            Instant instant = Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS);
            return new RequirementRepository.Cursor(OffsetDateTime.ofInstant(instant, ZoneOffset.UTC),
                    UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw ApiException.validation(List.of(new FieldError("cursor", "INVALID_FORMAT")));
        }
    }
}
