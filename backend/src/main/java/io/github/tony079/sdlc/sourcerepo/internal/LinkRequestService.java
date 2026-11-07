package io.github.tony079.sdlc.sourcerepo.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.identity.api.CurrentUser.TenantRole;
import io.github.tony079.sdlc.requirement.api.RequirementDirectory;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;
import io.github.tony079.sdlc.sourcerepo.internal.LinkRequestRepository.Link;
import io.github.tony079.sdlc.sourcerepo.internal.LinkRequestRepository.LinkRequest;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-18 (Option 1): the requirement owner issues a one-time request; a Client Approver of the same tenant redeems it with
 * a repository URL, which records the client's repository authorisation (Q-A). The approver learns nothing about the
 * requirement: the redeem response carries only the link id and status, and every failure (unknown, expired, used,
 * revoked, wrong role, other tenant, requirement no longer linkable) is the same 404 plus a security event.
 */
@Service
class LinkRequestService {

    static final int DEFAULT_HOURS = 72;
    static final int MIN_HOURS = 1;
    static final int MAX_HOURS = 168;
    static final String TOKEN_PREFIX = "rlr_";

    /** States in which a repository may be linked: before analysis, including manual intake (M2-C06). */
    static final Set<WorkflowState> LINKABLE = EnumSet.of(WorkflowState.DRAFT, WorkflowState.SUBMITTED,
            WorkflowState.MANUAL_INTAKE, WorkflowState.REPO_VALIDATING);

    private static final Pattern GITHUB_URL =
            Pattern.compile("^https://github\\.com/([A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38})/([A-Za-z0-9._-]{1,100})$");
    private static final Pattern TOKEN = Pattern.compile("^" + TOKEN_PREFIX + "[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LinkRequestRepository links;
    private final RegistrationRepository registrations;
    private final RequirementDirectory requirements;
    private final WorkflowEngine workflow;
    private final AuditWriter audit;
    private final SecurityEventRecorder securityEvents;

    LinkRequestService(LinkRequestRepository links, RegistrationRepository registrations,
                       RequirementDirectory requirements, WorkflowEngine workflow, AuditWriter audit,
                       SecurityEventRecorder securityEvents) {
        this.links = links;
        this.registrations = registrations;
        this.requirements = requirements;
        this.workflow = workflow;
        this.audit = audit;
        this.securityEvents = securityEvents;
    }

    record Issued(UUID requestId, String token, OffsetDateTime expiresAt) {
    }

    record Redeemed(UUID linkId, String status) {
    }

    record LinkStatus(PendingRequest pendingRequest, CurrentLink link, RegistrationRepository.Validation validation) {
    }

    record PendingRequest(UUID requestId, OffsetDateTime expiresAt) {
    }

    record CurrentLink(UUID linkId, String repositoryUrl, String status, OffsetDateTime authorizedAt) {
    }

    /** Owner only. Replaces any pending request of the requirement (the old token stops working). */
    @Transactional
    public Issued issue(UUID requirementId, Integer expiresInHours) {
        CurrentUser user = currentUser();
        int hours = expiresInHours == null ? DEFAULT_HOURS : expiresInHours;
        if (hours < MIN_HOURS || hours > MAX_HOURS) {
            throw ApiException.validation(java.util.List.of(new FieldError("expiresInHours", "OUT_OF_RANGE")));
        }
        requirements.findOwned(requirementId).orElseThrow(() -> notFound("REQUIREMENT:" + requirementId));
        WorkflowState state = workflow.lockState(requirementId);
        if (!LINKABLE.contains(state) || links.currentLink(requirementId)
                .filter(link -> link.status().equals("REGISTERED")).isPresent()) {
            throw ApiException.conflict("LINK_NOT_ALLOWED_IN_STATE",
                    "A repository link cannot be requested for this requirement now.");
        }
        links.revokePending(requirementId);
        String token = newToken();
        UUID id = links.insertRequest(user.tenantId(), requirementId, user.userId(), sha256(token), hours);
        audit.record(AuditAction.REPOSITORY_LINK_REQUESTED, "REPOSITORY_LINK_REQUEST", id, AuditResult.SUCCESS);
        LinkRequest stored = links.findById(id).orElseThrow();
        return new Issued(id, token, stored.expiresAt());
    }

    /** Issuing owner only; anything else is 404. */
    @Transactional
    public void revoke(UUID requestId) {
        CurrentUser user = currentUser();
        LinkRequest request = links.lockById(requestId)
                .filter(r -> r.createdBy().equals(user.userId()))
                .orElseThrow(() -> notFound("REPOSITORY_LINK_REQUEST:" + requestId));
        if (!links.revoke(request.id())) {
            throw notFound("REPOSITORY_LINK_REQUEST:" + requestId);
        }
        audit.record(AuditAction.REPOSITORY_LINK_REQUEST_REVOKED, "REPOSITORY_LINK_REQUEST", request.id(),
                AuditResult.SUCCESS);
    }

    /** Owner view of the link state of their own requirement. */
    @Transactional(readOnly = true)
    public LinkStatus status(UUID requirementId) {
        requirements.findOwned(requirementId).orElseThrow(() -> notFound("REQUIREMENT:" + requirementId));
        PendingRequest pending = links.pendingFor(requirementId).filter(r -> !r.expired())
                .map(r -> new PendingRequest(r.id(), r.expiresAt())).orElse(null);
        CurrentLink link = links.currentLink(requirementId)
                .map(l -> new CurrentLink(l.id(), l.repositoryUrl(), l.status(), l.authorizedAt())).orElse(null);
        return new LinkStatus(pending, link, registrations.validation(requirementId).orElse(null));
    }

    /**
     * Client Approver: redeem a token with a repository URL. The URL syntax is checked first (it reveals nothing about
     * any token); every token-related failure is the same 404.
     */
    @Transactional
    public Redeemed redeem(String token, String repositoryUrl) {
        CurrentUser user = currentUser();
        Matcher url = repositoryUrl == null ? null : GITHUB_URL.matcher(repositoryUrl.strip());
        if (url == null || repositoryUrl.strip().length() > 300 || !url.matches() || url.group(2).equals(".")
                || url.group(2).equals("..")) {
            throw ApiException.validation(java.util.List.of(new FieldError("repositoryUrl", "INVALID_URL")));
        }
        if (user.role() != TenantRole.CLIENT_APPROVER) {
            throw rejected(user, "WRONG_ROLE");
        }
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw rejected(user, "MALFORMED");
        }
        LinkRequest found = links.findByTokenHash(sha256(token)).orElseThrow(() -> rejected(user, "UNKNOWN"));
        // Lock order: workflow instance, then the request (as when issuing).
        WorkflowState state = workflow.lockState(found.requirementId());
        LinkRequest request = links.lockById(found.id()).orElseThrow(() -> rejected(user, "UNKNOWN"));
        if (!request.status().equals("PENDING")) {
            throw rejected(user, request.status());
        }
        if (request.expired()) {
            throw rejected(user, "EXPIRED");
        }
        if (!LINKABLE.contains(state)) {
            throw rejected(user, "NOT_LINKABLE");
        }
        Optional<Link> current = links.currentLink(request.requirementId());
        if (current.filter(link -> link.status().equals("REGISTERED")).isPresent()) {
            throw rejected(user, "ALREADY_REGISTERED");
        }
        current.ifPresent(link -> links.supersede(link.id()));
        links.markUsed(request.id(), user.userId());
        String cleanUrl = repositoryUrl.strip();
        UUID linkId = links.insertLink(user.tenantId(), request.requirementId(), request.id(), cleanUrl,
                url.group(1), url.group(2), user.userId());
        audit.record(AuditAction.REPOSITORY_LINK_ATTACHED, "REPOSITORY_LINK", linkId, AuditResult.SUCCESS);
        return new Redeemed(linkId, "AUTHORIZED");
    }

    private ApiException rejected(CurrentUser user, String reason) {
        securityEvents.record(SecurityEventType.LINK_REQUEST_REJECTED, Severity.MEDIUM, user.userId().toString(),
                reason);
        return ApiException.notFound();
    }

    private ApiException notFound(String target) {
        securityEvents.record(SecurityEventType.RESOURCE_NOT_FOUND, Severity.LOW,
                TenantContext.currentActor().map(UUID::toString).orElse(null), target);
        return ApiException.notFound();
    }

    private static CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        throw ApiException.notFound();
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
