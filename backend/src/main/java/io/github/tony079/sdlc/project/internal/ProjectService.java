package io.github.tony079.sdlc.project.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.ClientUsers;
import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.project.api.ProjectAccess;
import io.github.tony079.sdlc.project.api.ProjectAdministration.Membership;
import io.github.tony079.sdlc.project.api.ProjectRole;
import io.github.tony079.sdlc.project.api.ProjectView;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects of the organisation and their members (Garuda GD-02, GD-06; FR-ORG-04/06). Organisation Admins create,
 * edit and archive projects and manage members; every user sees the projects they belong to, and Organisation Admins
 * see all of them. The database enforces the same rules (row-level security and guard triggers).
 */
@Service
class ProjectService implements ProjectAccess, io.github.tony079.sdlc.project.api.ProjectAdministration {

    static final int LIST_LIMIT = 500;

    private final ProjectRepository projects;
    private final AuditWriter audit;
    private final SecurityEventRecorder securityEvents;

    ProjectService(ProjectRepository projects, AuditWriter audit, SecurityEventRecorder securityEvents) {
        this.projects = projects;
        this.audit = audit;
        this.securityEvents = securityEvents;
    }

    // ---- ProjectAccess (other modules) ------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<ProjectRole> roleOf(UUID projectId) {
        return ClientUsers.current().flatMap(user -> projects.roleOf(projectId, user.userId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean isActive(UUID projectId) {
        return projects.status(projectId).map("ACTIVE"::equals).orElse(false);
    }

    // ---- Projects ---------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ProjectView> list(boolean includeArchived) {
        CurrentUser user = ClientUsers.require();
        return projects.findAll(user.userId(), includeArchived && user.orgAdmin(), LIST_LIMIT);
    }

    /** 404 when the project does not exist, belongs to another organisation or is not visible to the caller. */
    @Transactional(readOnly = true)
    public ProjectView get(UUID id) {
        return projects.findById(id, ClientUsers.require().userId()).orElseThrow(() -> notFound(id));
    }

    @Transactional
    public ProjectView create(String name, String description) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        UUID id;
        try {
            id = projects.insert(TenantContext.requireTenant(), name(name), description(description), admin.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PROJECT_NAME_TAKEN", "A project with this name already exists.");
        }
        audit.record(AuditAction.PROJECT_CREATED, "PROJECT", id, AuditResult.SUCCESS);
        return projects.findById(id, admin.userId()).orElseThrow();
    }

    @Transactional
    public ProjectView update(UUID id, String name, String description) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        get(id);
        try {
            if (!projects.update(id, name(name), description(description))) {
                throw ApiException.conflict("PROJECT_ARCHIVED", "An archived project cannot be changed.");
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PROJECT_NAME_TAKEN", "A project with this name already exists.");
        }
        audit.record(AuditAction.PROJECT_UPDATED, "PROJECT", id, AuditResult.SUCCESS);
        return projects.findById(id, admin.userId()).orElseThrow();
    }

    @Transactional
    public ProjectView archive(UUID id) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        get(id);
        if (!projects.archive(id)) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "The project is already archived.");
        }
        audit.record(AuditAction.PROJECT_ARCHIVED, "PROJECT", id, AuditResult.SUCCESS);
        return projects.findById(id, admin.userId()).orElseThrow();
    }

    // ---- Members ----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ProjectRepository.Member> members(UUID projectId) {
        ClientUsers.requireOrgAdmin();
        get(projectId);
        return projects.members(projectId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Membership> membershipsOf(UUID userId) {
        ClientUsers.requireOrgAdmin();
        return projects.membershipsOf(userId).stream()
                .map(m -> new Membership(m.id(), m.projectId(), m.projectName(), m.role())).toList();
    }

    @Override
    @Transactional
    public void addMember(UUID projectId, UUID userId, ProjectRole role) {
        addProjectMember(projectId, userId, role);
    }

    /** Adds a user (INVITED or ACTIVE) to a project with a role. Used directly and by invitations. */
    @Transactional
    public ProjectRepository.Member addProjectMember(UUID projectId, UUID userId, ProjectRole role) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        ProjectView project = get(projectId);
        if (!"ACTIVE".equals(project.status())) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "Members cannot be added to an archived project.");
        }
        if (role == null) {
            throw ApiException.validation("Choose a role: REQUESTER, APPROVER or VIEWER.");
        }
        if (!projects.userExists(userId)) {
            throw ApiException.notFound();
        }
        UUID id;
        try {
            id = projects.addMember(TenantContext.requireTenant(), projectId, userId, role, admin.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ALREADY_MEMBER", "This user is already a member of the project.");
        }
        audit.record(AuditAction.PROJECT_MEMBER_ADDED, "PROJECT_MEMBER", id, AuditResult.SUCCESS);
        return projects.member(id).orElseThrow();
    }

    @Transactional
    public ProjectRepository.Member changeRole(UUID memberId, ProjectRole role) {
        ClientUsers.requireOrgAdmin();
        if (role == null) {
            throw ApiException.validation("Choose a role: REQUESTER, APPROVER or VIEWER.");
        }
        projects.member(memberId).orElseThrow(ApiException::notFound);
        projects.changeRole(memberId, role);
        audit.record(AuditAction.PROJECT_MEMBER_ROLE_CHANGED, "PROJECT_MEMBER", memberId, AuditResult.SUCCESS);
        return projects.member(memberId).orElseThrow();
    }

    @Transactional
    public void removeMember(UUID memberId) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        projects.member(memberId).orElseThrow(ApiException::notFound);
        projects.remove(memberId, admin.userId());
        audit.record(AuditAction.PROJECT_MEMBER_REMOVED, "PROJECT_MEMBER", memberId, AuditResult.SUCCESS);
    }

    private ApiException notFound(UUID id) {
        securityEvents.record(SecurityEventType.RESOURCE_NOT_FOUND, Severity.LOW,
                TenantContext.currentActor().map(UUID::toString).orElse(null), "PROJECT:" + id);
        return ApiException.notFound();
    }

    private static String name(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 200) {
            throw ApiException.validation("The project name must be 1 to 200 characters.");
        }
        return trimmed;
    }

    private static String description(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String trimmed = description.strip();
        if (trimmed.length() > 2000) {
            throw ApiException.validation("The description must be at most 2000 characters.");
        }
        return trimmed;
    }
}
