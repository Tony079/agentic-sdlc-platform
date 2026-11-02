package io.github.tony079.sdlc.project.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.project.api.ProjectView;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProjectService {

    static final int LIST_LIMIT = 100;

    private final ProjectRepository projects;
    private final AuditWriter audit;
    private final SecurityEventRecorder securityEvents;

    ProjectService(ProjectRepository projects, AuditWriter audit, SecurityEventRecorder securityEvents) {
        this.projects = projects;
        this.audit = audit;
        this.securityEvents = securityEvents;
    }

    @Transactional(readOnly = true)
    public List<ProjectView> list() {
        return projects.findAll(LIST_LIMIT);
    }

    /**
     * A miss is answered with 404 whether the id does not exist or belongs to another tenant (PRD FR-AUTH-04),
     * and is recorded as a security event (FR-AUTH-13) in its own transaction.
     */
    @Transactional(readOnly = true)
    public ProjectView get(UUID id) {
        return projects.findById(id).orElseThrow(() -> {
            securityEvents.record(SecurityEventType.RESOURCE_NOT_FOUND, Severity.LOW,
                    TenantContext.currentActor().map(UUID::toString).orElse(null), "PROJECT:" + id);
            return ApiException.notFound();
        });
    }

    @Transactional
    public ProjectView create(String name) {
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 200) {
            throw ApiException.validation("The project name must be 1 to 200 characters.");
        }
        ProjectView created;
        try {
            created = projects.insert(TenantContext.requireTenant(), trimmed, TenantContext.currentActor().orElseThrow());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PROJECT_NAME_TAKEN", "A project with this name already exists.");
        }
        audit.record(AuditAction.PROJECT_CREATED, "PROJECT", created.id(), AuditResult.SUCCESS);
        return created;
    }
}
