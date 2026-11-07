package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffAccess.StaffContext;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.api.StaffPrincipal;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.requirement.api.RequirementDirectory;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;
import io.github.tony079.sdlc.sourcerepo.internal.RegistrationRepository.AuthorizedLink;
import io.github.tony079.sdlc.sourcerepo.internal.RegistrationRepository.PendingLink;
import io.github.tony079.sdlc.sourcerepo.internal.RegistrationRepository.RepositorySummary;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Final repository registration (C-01/O-05): staff with MFA, an active assignment to the tenant and the
 * REPOSITORY_REGISTER capability (re-checked against the database on every call). The repository identity comes only
 * from the Client Approver's AUTHORIZED link (Q-A), never from staff input. The API holds no GitHub credentials:
 * verification happens in the worker after the RepositoryRegistered event.
 */
@Service
class RegistrationService {

    static final String AGGREGATE = "REPOSITORY";
    private static final Set<WorkflowState> REGISTRABLE = EnumSet.of(WorkflowState.DRAFT, WorkflowState.SUBMITTED,
            WorkflowState.MANUAL_INTAKE, WorkflowState.REPO_VALIDATING);

    private final RegistrationRepository repositories;
    private final StaffAccess staffAccess;
    private final RequirementDirectory requirements;
    private final WorkflowEngine workflow;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final SecurityEventRecorder securityEvents;

    RegistrationService(RegistrationRepository repositories, StaffAccess staffAccess, RequirementDirectory requirements,
                        WorkflowEngine workflow, OutboxPublisher outbox, AuditWriter audit,
                        SecurityEventRecorder securityEvents) {
        this.repositories = repositories;
        this.staffAccess = staffAccess;
        this.requirements = requirements;
        this.workflow = workflow;
        this.outbox = outbox;
        this.audit = audit;
        this.securityEvents = securityEvents;
    }

    record Registered(UUID repositoryId, String status) {
    }

    @Transactional
    public Registered register(UUID projectId, UUID linkId) {
        StaffContext staff = staffAccess.requireCapability(StaffCapability.REPOSITORY_REGISTER);
        AuthorizedLink link = repositories.lockLink(linkId)
                .filter(l -> l.status().equals("AUTHORIZED"))
                .orElseThrow(() -> notFound(staff, "REPOSITORY_LINK:" + linkId));
        if (!requirements.projectOf(link.requirementId()).map(projectId::equals).orElse(false)) {
            throw notFound(staff, "PROJECT:" + projectId);
        }
        WorkflowState state = workflow.lockState(link.requirementId());
        if (!REGISTRABLE.contains(state)) {
            throw ApiException.conflict("REGISTRATION_NOT_ALLOWED_IN_STATE",
                    "The repository cannot be registered for this requirement now.");
        }
        if (repositories.requirementRegistered(link.requirementId())) {
            throw ApiException.conflict("REPOSITORY_ALREADY_REGISTERED",
                    "A repository is already registered for this requirement.");
        }
        UUID repositoryId = repositories.findRepository(link.owner(), link.name())
                .orElseGet(() -> repositories.insertRepository(staff.tenantId(), link.owner(), link.name(),
                        link.repositoryUrl(), staff.staffId()));
        repositories.linkProject(staff.tenantId(), projectId, repositoryId);
        repositories.insertRequirementRepository(staff.tenantId(), link.requirementId(), repositoryId, link.id());
        repositories.markLinkRegistered(link.id());
        audit.record(AuditAction.REPOSITORY_REGISTERED, AGGREGATE, repositoryId, AuditResult.SUCCESS);
        outbox.publish(AGGREGATE, repositoryId, EventTypes.REPOSITORY_REGISTERED,
                Map.of("repositoryId", repositoryId.toString(), "requirementId", link.requirementId().toString()));
        return new Registered(repositoryId, repositories.repositoryStatus(repositoryId));
    }

    /** AUTHORIZED links of the active tenant awaiting registration (staff with the capability). No requirement data. */
    @Transactional(readOnly = true)
    public List<PendingLink> pendingLinks() {
        staffAccess.requireCapability(StaffCapability.REPOSITORY_REGISTER);
        return repositories.pendingLinks();
    }

    /** Status only: tenant users of the project's tenant, or staff with an active assignment. No credentials. */
    @Transactional(readOnly = true)
    public List<RepositorySummary> list(UUID projectId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof StaffPrincipal) {
            staffAccess.requireActiveTenant();
        }
        return repositories.projectRepositories(projectId);
    }

    private ApiException notFound(StaffContext staff, String target) {
        securityEvents.record(SecurityEventType.RESOURCE_NOT_FOUND, Severity.LOW, staff.staffId().toString(), target);
        return ApiException.notFound();
    }
}
