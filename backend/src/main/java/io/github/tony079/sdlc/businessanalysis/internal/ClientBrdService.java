package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.ClientBrd;
import io.github.tony079.sdlc.businessanalysis.internal.BrdViews.NotificationView;
import io.github.tony079.sdlc.businessanalysis.internal.ReviewRepository.VersionRow;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.requirement.api.RequirementDirectory;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.Transition;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Client side of R1-M6 (TDD 19.5): the requirement owner sees only the RELEASED BRD version (404 before release,
 * AC-CORE-08), with evidence references but no source excerpts (AC-EXT-29), and may request changes (T27; C-09
 * provisional: the requirement owner, because only the owner can see the requirement under C-18). In-app notifications.
 */
@Service
@Profile("!migrate & !provision")
class ClientBrdService {

    private final ReviewRepository reviews;
    private final RequirementDirectory requirements;
    private final WorkflowEngine workflow;
    private final AuditWriter audit;
    private final SecurityEventRecorder securityEvents;

    ClientBrdService(ReviewRepository reviews, RequirementDirectory requirements, WorkflowEngine workflow,
                     AuditWriter audit, SecurityEventRecorder securityEvents) {
        this.reviews = reviews;
        this.requirements = requirements;
        this.workflow = workflow;
        this.audit = audit;
        this.securityEvents = securityEvents;
    }

    @Transactional
    public ClientBrd released(UUID requirementId) {
        owned(requirementId);
        VersionRow version = reviews.latestWithStatus(requirementId, Set.of("RELEASED")).orElseThrow(ApiException::notFound);
        audit.record(AuditAction.BRD_VIEWED, "BRD_VERSION", version.id(), AuditResult.SUCCESS);
        return new ClientBrd(requirementId, workflow.state(requirementId).name(), reviews.view(version.id(), false));
    }

    @Transactional
    public WorkflowState requestChanges(UUID requirementId, String text) {
        if (text == null || text.strip().length() < 10 || text.length() > 5000) {
            throw ApiException.validation(List.of(new FieldError("text", "LENGTH")));
        }
        owned(requirementId);
        if (workflow.lockState(requirementId) != WorkflowState.SHARED_WITH_CLIENT) {
            throw ApiException.conflict("ILLEGAL_TRANSITION", "Changes can be requested only on a shared BRD.");
        }
        VersionRow version = reviews.latestWithStatus(requirementId, Set.of("RELEASED")).orElseThrow(ApiException::notFound);
        reviews.insertClientChange(TenantContext.requireTenant(), requirementId, version.id(), text.strip(),
                TenantContext.currentActor().orElseThrow());
        WorkflowState to = workflow.transition(requirementId, Transition.T27_CLIENT_CHANGES, null);
        audit.record(AuditAction.CLIENT_CHANGES_REQUESTED, "REQUIREMENT", requirementId, AuditResult.SUCCESS);
        return to;
    }

    @Transactional(readOnly = true)
    public List<NotificationView> notifications() {
        return reviews.notifications(TenantContext.currentActor().orElseThrow());
    }

    @Transactional
    public void markRead(UUID notificationId) {
        if (!reviews.markRead(notificationId, TenantContext.currentActor().orElseThrow())) {
            throw ApiException.notFound();
        }
    }

    private void owned(UUID requirementId) {
        if (requirements.findOwned(requirementId).isEmpty()) {
            securityEvents.record(SecurityEventType.RESOURCE_NOT_FOUND, Severity.LOW,
                    TenantContext.currentActor().map(UUID::toString).orElse(null), "REQUIREMENT:" + requirementId);
            throw ApiException.notFound();
        }
    }
}
