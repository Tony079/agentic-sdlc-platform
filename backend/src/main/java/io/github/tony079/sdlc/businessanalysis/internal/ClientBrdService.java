package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.Map;
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

    static final int REVISIONS_INCLUDED = 3;

    /**
     * GD-24: the BRD for the client. While in review (and until it is released) only the requirement's author and
     * the project's BRD Approvers see it; once released, every member who can see the requirement does.
     */
    @Transactional
    public ClientBrd released(UUID requirementId) {
        var visible = owned(requirementId);
        // A closed requirement (not feasible) keeps its withdrawn version readable for the author and approvers.
        VersionRow version = reviews.latestWithStatus(requirementId,
                        Set.of("IN_REVIEW", "APPROVED_INTERNAL", "RELEASED", "WITHDRAWN"))
                .orElseThrow(ApiException::notFound);
        UUID me = TenantContext.currentActor().orElseThrow();
        boolean author = me.equals(visible.ownerId());
        boolean approver = reviews.isBrdApprover(visible.projectId());
        if (!"RELEASED".equals(version.status()) && !author && !approver) {
            throw ApiException.notFound();
        }
        audit.record(AuditAction.BRD_VIEWED, "BRD_VERSION", version.id(), AuditResult.SUCCESS);
        WorkflowState state = workflow.state(requirementId);
        return new ClientBrd(requirementId, state.name(), reviews.view(version.id(), false),
                review(requirementId, version, state, author, approver));
    }

    private BrdViews.ClientReview review(UUID requirementId, VersionRow version, WorkflowState state, boolean author,
                                         boolean approver) {
        List<ReviewRepository.ClientDecision> decisions = reviews.clientDecisions(version.id());
        var confirmed = decisions.stream().filter(d -> "AUTHOR_CONFIRMED".equals(d.kind())).findFirst().orElse(null);
        var approved = decisions.stream().filter(d -> "APPROVED".equals(d.kind())).findFirst().orElse(null);
        List<String> unaccepted = reviews.unacceptedFlags(version.id());
        List<String> accepted = decisions.stream().filter(d -> "FLAG_ACCEPTED".equals(d.kind()))
                .map(ReviewRepository.ClientDecision::itemKey).toList();
        ReviewRepository.Feasibility f = reviews.feasibility(version.id());
        List<String> reasons = new java.util.ArrayList<>();
        tools.jackson.databind.json.JsonMapper.builder().build().readTree(f.reasonsJson())
                .forEach(n -> reasons.add(n.asString()));
        int used = reviews.clientRevisionRequests(requirementId);
        var price = used >= REVISIONS_INCLUDED ? reviews.revisionPrice(requirementId).orElse(null) : null;
        boolean inReview = state == WorkflowState.INTERNAL_REVIEW && "IN_REVIEW".equals(version.status());
        String stage = state == WorkflowState.REJECTED && "NOT_FEASIBLE".equals(f.verdict()) ? "CLOSED_NOT_FEASIBLE"
                : "RELEASED".equals(version.status()) ? "APPROVED"
                : "APPROVED_INTERNAL".equals(version.status()) ? "PUBLISHING"
                : confirmed != null ? "AWAITING_APPROVAL" : "AUTHOR_REVIEW";
        String blocked = null;
        if (inReview && confirmed == null && author && !unaccepted.isEmpty()) {
            blocked = unaccepted.size() + " flagged statement(s) need your decision before you confirm.";
        } else if (inReview && confirmed != null && approver && author) {
            blocked = "A BRD Approver other than the author gives the final approval.";
        }
        return new BrdViews.ClientReview(stage, f.verdict(), reasons, unaccepted, accepted,
                confirmed == null ? null : confirmed.decidedByName(), confirmed == null ? null : confirmed.decidedAt(),
                approved == null ? null : approved.decidedByName(), used, REVISIONS_INCLUDED,
                price == null ? null : price.currency(), price == null ? null : price.amount(), author, approver,
                inReview && author && confirmed == null, inReview && author && confirmed == null && unaccepted.isEmpty(),
                inReview && approver && !author && confirmed != null, inReview && (author || approver), blocked);
    }

    /**
     * The approved (released) BRD as a document for PDF or Word export. Any member who can see the requirement may
     * export it; a BRD still in review is not exportable, so an unapproved draft never circulates as a file.
     */
    @Transactional
    public BrdDocument export(UUID requirementId, java.time.ZoneId zone) {
        owned(requirementId);
        VersionRow version = reviews.latestWithStatus(requirementId, Set.of("RELEASED"))
                .orElseThrow(() -> ApiException.conflict("BRD_NOT_APPROVED",
                        "Only an approved BRD can be downloaded. This BRD is not approved yet."));
        ReviewRepository.Feasibility f = reviews.feasibility(version.id());
        List<String> reasons = new java.util.ArrayList<>();
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        if (f.reasonsJson() != null) {
            mapper.readTree(f.reasonsJson()).forEach(n -> reasons.add(n.asString()));
        }
        BrdViews.VersionView view = reviews.view(version.id(), false);
        List<String> excluded = new java.util.ArrayList<>();
        if (view.excludedDocuments() != null) {
            mapper.readTree(view.excludedDocuments()).forEach(n -> excluded.add(n.path("fileName").asString("document")
                    + " (" + n.path("state").asString("not processed").toLowerCase().replace('_', ' ') + ")"));
        }
        UUID me = TenantContext.currentActor().orElseThrow();
        BrdDocument document = BrdDocument.of(reviews.exportHeader(requirementId), view, f.verdict(), reasons,
                reviews.clientDecisions(version.id()), excluded, reviews.displayName(me), zone);
        audit.record(AuditAction.BRD_EXPORTED, "BRD_VERSION", version.id(), AuditResult.SUCCESS);
        return document;
    }

    /** The author accepts a flagged statement as it stands (the evidence could not be verified automatically). */
    @Transactional
    public ClientBrd acceptFlag(UUID requirementId, String itemKey) {
        Decision d = decision(requirementId);
        if (!d.author()) {
            throw forbidden("Only the requirement's author reviews its BRD.");
        }
        if (!reviews.isFlagged(d.version().id(), itemKey)) {
            throw ApiException.notFound();
        }
        if (!reviews.unacceptedFlags(d.version().id()).contains(itemKey)) {
            return released(requirementId);
        }
        reviews.insertClientDecision(TenantContext.requireTenant(), d.projectId(), requirementId, d.version().id(),
                "FLAG_ACCEPTED", itemKey, null, d.me());
        audit.record(AuditAction.BRD_FLAG_ACKNOWLEDGED, "BRD_VERSION", d.version().id(), AuditResult.SUCCESS);
        return released(requirementId);
    }

    /** The author confirms the BRD; the project's BRD Approvers are asked for the final approval. */
    @Transactional
    public ClientBrd confirm(UUID requirementId) {
        Decision d = decision(requirementId);
        if (!d.author()) {
            throw forbidden("Only the requirement's author confirms its BRD.");
        }
        if (!reviews.unacceptedFlags(d.version().id()).isEmpty()) {
            throw ApiException.conflict("FLAGS_NOT_ACCEPTED",
                    "Accept or request changes to every flagged statement before you confirm.");
        }
        if (reviews.clientDecisions(d.version().id()).stream().anyMatch(x -> "AUTHOR_CONFIRMED".equals(x.kind()))) {
            return released(requirementId);
        }
        reviews.insertClientDecision(TenantContext.requireTenant(), d.projectId(), requirementId, d.version().id(),
                "AUTHOR_CONFIRMED", null, null, d.me());
        for (UUID approver : reviews.brdApprovers(d.projectId())) {
            if (!approver.equals(d.me())) {
                reviews.notify(TenantContext.requireTenant(), approver, "BRD_AWAITING_APPROVAL", requirementId);
            }
        }
        audit.record(AuditAction.BRD_APPROVED, "BRD_VERSION", d.version().id(), AuditResult.SUCCESS);
        return released(requirementId);
    }

    /** A BRD Approver other than the author approves; the worker then releases it when the automated gates pass. */
    @Transactional
    public ClientBrd approve(UUID requirementId, String expectedContentHash) {
        Decision d = decision(requirementId);
        if (d.author() || !d.approver()) {
            throw forbidden("A BRD Approver of the project other than the author gives the final approval.");
        }
        if (expectedContentHash != null && !expectedContentHash.equals(d.version().contentHash())) {
            throw ApiException.conflict("VERSION_CHANGED", "The BRD changed since you opened it. Reload it.");
        }
        if (reviews.clientDecisions(d.version().id()).stream().noneMatch(x -> "AUTHOR_CONFIRMED".equals(x.kind()))) {
            throw ApiException.conflict("AUTHOR_CONFIRMATION_REQUIRED", "The author has not confirmed this BRD yet.");
        }
        reviews.insertClientDecision(TenantContext.requireTenant(), d.projectId(), requirementId, d.version().id(),
                "APPROVED", null, null, d.me());
        reviews.setVersionStatus(d.version().id(), "APPROVED_INTERNAL");
        workflow.transition(requirementId, Transition.T21_APPROVE_INTERNAL, "Approved by a BRD Approver");
        audit.record(AuditAction.BRD_APPROVED, "BRD_VERSION", d.version().id(), AuditResult.SUCCESS);
        return released(requirementId);
    }

    /**
     * The author or a BRD Approver requests changes; the agent produces a new version with the request. Three are
     * included per requirement; each further one is charged at the BRD's size price and needs explicit consent.
     */
    @Transactional
    public ClientBrd requestRevision(UUID requirementId, String text, boolean acceptCharge) {
        if (text == null || text.strip().length() < 10 || text.length() > 5000) {
            throw ApiException.validation(List.of(new FieldError("text", "LENGTH")));
        }
        Decision d = decision(requirementId);
        if (!d.author() && !d.approver()) {
            throw forbidden("The author or a BRD Approver requests changes.");
        }
        int next = reviews.clientRevisionRequests(requirementId) + 1;
        if (next > REVISIONS_INCLUDED) {
            var price = reviews.revisionPrice(requirementId).orElse(null);
            if (price != null) {
                if (!acceptCharge) {
                    throw ApiException.conflict("REVISION_CHARGE_REQUIRED", "The " + REVISIONS_INCLUDED
                            + " included change requests are used. This one is charged " + price.currency() + " "
                            + price.amount().toPlainString() + ".");
                }
                reviews.insertRevisionCharge(TenantContext.requireTenant(), price, requirementId, next, d.me());
            }
        }
        reviews.insertClientDecision(TenantContext.requireTenant(), d.projectId(), requirementId, d.version().id(),
                "CHANGES_REQUESTED", null, text.strip(), d.me());
        workflow.transition(requirementId, Transition.T22_REQUEST_REVISION, "Changes requested by the client");
        audit.record(AuditAction.BRD_REVISION_REQUESTED, "BRD_VERSION", d.version().id(), AuditResult.SUCCESS);
        return released(requirementId);
    }

    private record Decision(UUID me, UUID projectId, boolean author, boolean approver, VersionRow version) {
    }

    private Decision decision(UUID requirementId) {
        var visible = owned(requirementId);
        UUID me = TenantContext.currentActor().orElseThrow();
        if (workflow.lockState(requirementId) != WorkflowState.INTERNAL_REVIEW) {
            throw ApiException.conflict("BRD_NOT_IN_REVIEW", "This BRD is not waiting for a review decision.");
        }
        VersionRow version = reviews.latestWithStatus(requirementId, Set.of("IN_REVIEW")).orElseThrow(ApiException::notFound);
        boolean author = me.equals(visible.ownerId());
        boolean approver = reviews.isBrdApprover(visible.projectId());
        if (!author && !approver) {
            throw ApiException.notFound();
        }
        return new Decision(me, visible.projectId(), author, approver, version);
    }

    private static ApiException forbidden(String message) {
        return new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "BRD_DECISION_NOT_ALLOWED", message);
    }

    /**
     * C-09: a client change on a shared BRD is a formal change request; it changes nothing until assessed, reviewed and
     * accepted by a Client Approver ({@link ChangeRequestService}). Kept for the existing endpoint: the text becomes the
     * requested change with no field edits.
     */
    @Transactional
    public UUID requestChanges(UUID requirementId, String text, ChangeRequestService changeRequests) {
        if (text == null || text.strip().length() < 10 || text.length() > 5000) {
            throw ApiException.validation(List.of(new FieldError("text", "LENGTH")));
        }
        owned(requirementId);
        return changeRequests.submit(requirementId, text, text, "Not specified by the client.", Map.of()).id();
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

    /** Every member who may see the requirement may see its released BRD (GD-05). */
    private RequirementDirectory.VisibleRequirement owned(UUID requirementId) {
        return requirements.findVisible(requirementId).orElseThrow(() -> {
            securityEvents.record(SecurityEventType.RESOURCE_NOT_FOUND, Severity.LOW,
                    TenantContext.currentActor().map(UUID::toString).orElse(null), "REQUIREMENT:" + requirementId);
            return ApiException.notFound();
        });
    }
}
