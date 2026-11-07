package io.github.tony079.sdlc.identity.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.common.api.RateLimitedException;
import io.github.tony079.sdlc.identity.api.StaffPrincipal;
import io.github.tony079.sdlc.identity.internal.StaffRepository.Assignment;
import io.github.tony079.sdlc.identity.internal.StaffRepository.StaffRecord;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff login, TOTP MFA and active-tenant selection (owner decisions O-06, O-11). Separate from the tenant login: staff
 * never receive a client role. Every response is content-free; the TOTP secret appears only in the one enrolment
 * response.
 */
@RestController
@RequestMapping("/api/v1/staff")
@ConditionalOnWebApplication
class StaffAuthController {

    private static final String ISSUER = "Agentic SDLC Platform";

    private final StaffAuthService auth;
    private final LoginThrottle throttle;
    private final SecurityEventRecorder securityEvents;
    private final PlatformAuditWriter audit;
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    StaffAuthController(StaffAuthService auth, LoginThrottle throttle, SecurityEventRecorder securityEvents,
                        PlatformAuditWriter audit, SecurityContextRepository contextRepository,
                        CsrfTokenRepository csrfTokenRepository) {
        this.auth = auth;
        this.throttle = throttle;
        this.securityEvents = securityEvents;
        this.audit = audit;
        this.contextRepository = contextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @PostMapping("/auth/login")
    MfaStatusResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                            HttpServletResponse response) {
        String accountKey = "staff|" + AuthKeys.sha256(body.email().trim().toLowerCase(java.util.Locale.ROOT));
        String clientKey = request.getRemoteAddr();
        Optional<java.time.Duration> blocked = throttle.blockedFor(accountKey, clientKey);
        if (blocked.isPresent()) {
            securityEvents.record(SecurityEventType.LOGIN_THROTTLED, Severity.MEDIUM, accountKey, null);
            throw new RateLimitedException(blocked.get());
        }
        Optional<StaffRecord> staff = auth.authenticate(body.email(), body.password());
        if (staff.isEmpty()) {
            throttle.recordFailure(accountKey, clientKey);
            securityEvents.record(SecurityEventType.LOGIN_FAILED, Severity.LOW, accountKey, null);
            audit.record(null, "STAFF_LOGIN", null, null, null, "DENIED");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "The email or password is incorrect.");
        }
        throttle.recordSuccess(accountKey);
        StaffRecord record = staff.get();
        boolean enrolled = record.mfaEnrolledAt() != null;
        establish(new StaffMfaPending(record.id(), enrolled), StaffMfaPending.AUTHORITY, request, response);
        audit.record(record.id(), "STAFF_LOGIN", "STAFF_USER", record.id().toString(), null, "SUCCESS");
        return new MfaStatusResponse(enrolled ? "MFA_REQUIRED" : "MFA_ENROLMENT_REQUIRED");
    }

    @PostMapping("/auth/mfa/enrolment")
    EnrolmentResponse startEnrolment(HttpServletRequest request) {
        StaffMfaPending pending = pending();
        if (pending.enrolled()) {
            throw new ApiException(HttpStatus.CONFLICT, "MFA_ALREADY_ENROLLED", "MFA is already enrolled.");
        }
        byte[] secret = auth.startEnrolment(pending.staffId()).orElseThrow(() -> {
            audit.record(pending.staffId(), "STAFF_MFA_ENROLLED", "STAFF_USER", pending.staffId().toString(), null,
                    "DENIED");
            return new ApiException(HttpStatus.FORBIDDEN, "MFA_ENROLMENT_CLOSED",
                    "MFA enrolment is not open for this account. Ask a staff administrator.");
        });
        String base32 = Totp.base32(secret);
        String email = auth.find(pending.staffId()).map(StaffRecord::email).orElse("staff");
        String label = URLEncoder.encode(ISSUER + ":" + email, StandardCharsets.UTF_8).replace("+", "%20");
        String uri = "otpauth://totp/" + label + "?secret=" + base32 + "&issuer="
                + URLEncoder.encode(ISSUER, StandardCharsets.UTF_8).replace("+", "%20")
                + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.STEP_SECONDS;
        return new EnrolmentResponse(base32, uri);
    }

    @PostMapping("/auth/mfa/enrolment/confirm")
    StaffMeResponse confirmEnrolment(@Valid @RequestBody CodeRequest body, HttpServletRequest request,
                                     HttpServletResponse response) {
        StaffMfaPending pending = pending();
        throttleMfa(pending.staffId(), request);
        if (pending.enrolled() || !auth.confirmEnrolment(pending.staffId(), body.code())) {
            return mfaFailed(pending.staffId(), request);
        }
        audit.record(pending.staffId(), "STAFF_MFA_ENROLLED", "STAFF_USER", pending.staffId().toString(), null,
                "SUCCESS");
        return completeMfa(pending.staffId(), request, response);
    }

    @PostMapping("/auth/mfa/verify")
    StaffMeResponse verify(@Valid @RequestBody CodeRequest body, HttpServletRequest request,
                           HttpServletResponse response) {
        StaffMfaPending pending = pending();
        throttleMfa(pending.staffId(), request);
        if (!pending.enrolled() || !auth.verify(pending.staffId(), body.code())) {
            return mfaFailed(pending.staffId(), request);
        }
        audit.record(pending.staffId(), "STAFF_MFA_VERIFIED", "STAFF_USER", pending.staffId().toString(), null,
                "SUCCESS");
        return completeMfa(pending.staffId(), request, response);
    }

    @GetMapping("/me")
    StaffMeResponse me() {
        return describe(staff());
    }

    @PutMapping("/session/active-tenant")
    StaffMeResponse setActiveTenant(@Valid @RequestBody ActiveTenantRequest body, HttpServletRequest request,
                                    HttpServletResponse response) {
        StaffPrincipal principal = staff();
        Optional<Assignment> assignment = auth.assignment(principal.staffId(), body.tenantId());
        if (assignment.isEmpty()) {
            securityEvents.record(SecurityEventType.STAFF_ACCESS_DENIED, Severity.MEDIUM,
                    principal.staffId().toString(), "ACTIVE_TENANT_NOT_ASSIGNED");
            audit.record(principal.staffId(), "STAFF_ACTIVE_TENANT_SET", "TENANT", null, null, "DENIED");
            // Same answer for unknown, inactive and unassigned tenants.
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed.");
        }
        StaffPrincipal updated = principal.withActiveTenant(assignment.get().tenantId(), assignment.get().tenantName());
        establish(updated, "ROLE_STAFF", request, response);
        audit.record(principal.staffId(), "STAFF_ACTIVE_TENANT_SET", "TENANT", updated.activeTenantId().toString(),
                updated.activeTenantId(), "SUCCESS");
        return describe(updated);
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Object principal = currentPrincipal();
        UUID staffId = principal instanceof StaffPrincipal p ? p.staffId()
                : principal instanceof StaffMfaPending p ? p.staffId() : null;
        audit.record(staffId, "STAFF_LOGOUT", "STAFF_USER", staffId == null ? null : staffId.toString(), null,
                "SUCCESS");
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfTokenRepository.saveToken(null, request, response);
        return ResponseEntity.noContent().build();
    }

    private StaffMeResponse completeMfa(UUID staffId, HttpServletRequest request, HttpServletResponse response) {
        throttle.recordSuccess("staff-mfa|" + staffId);
        StaffRecord record = auth.find(staffId).orElseThrow(
                () -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required."));
        StaffPrincipal principal = new StaffPrincipal(record.id(), record.email(), record.displayName(), null, null);
        establish(principal, "ROLE_STAFF", request, response);
        return describe(principal);
    }

    private StaffMeResponse mfaFailed(UUID staffId, HttpServletRequest request) {
        throttle.recordFailure("staff-mfa|" + staffId, request.getRemoteAddr());
        securityEvents.record(SecurityEventType.MFA_FAILED, Severity.MEDIUM, staffId.toString(), null);
        audit.record(staffId, "STAFF_MFA_FAILED", "STAFF_USER", staffId.toString(), null, "DENIED");
        throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA_FAILED", "The code is incorrect or was already used.");
    }

    private void throttleMfa(UUID staffId, HttpServletRequest request) {
        Optional<java.time.Duration> blocked = throttle.blockedFor("staff-mfa|" + staffId, request.getRemoteAddr());
        if (blocked.isPresent()) {
            securityEvents.record(SecurityEventType.LOGIN_THROTTLED, Severity.MEDIUM, staffId.toString(), "MFA");
            throw new RateLimitedException(blocked.get());
        }
    }

    private void establish(Object principal, String authority, HttpServletRequest request,
                           HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(authority))));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
    }

    private StaffMeResponse describe(StaffPrincipal principal) {
        List<TenantSummary> tenants = auth.assignments(principal.staffId()).stream()
                .map(a -> new TenantSummary(a.tenantId(), a.tenantName())).toList();
        TenantSummary active = principal.activeTenantId() == null ? null
                : new TenantSummary(principal.activeTenantId(), principal.activeTenantName());
        return new StaffMeResponse(principal.staffId(), principal.email(), principal.displayName(), active, tenants,
                auth.capabilities(principal.staffId()));
    }

    private static Object currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getPrincipal();
    }

    private static StaffMfaPending pending() {
        if (currentPrincipal() instanceof StaffMfaPending pending) {
            return pending;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
    }

    private static StaffPrincipal staff() {
        if (currentPrincipal() instanceof StaffPrincipal principal) {
            return principal;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.");
    }

    record LoginRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 200) String password) {
    }

    record CodeRequest(@NotBlank @Size(max = 6) String code) {
    }

    record ActiveTenantRequest(@NotNull UUID tenantId) {
    }

    record MfaStatusResponse(String status) {
    }

    record EnrolmentResponse(String secret, String otpauthUri) {
    }

    record TenantSummary(UUID id, String name) {
    }

    record StaffMeResponse(UUID staffId, String email, String displayName, TenantSummary activeTenant,
                           List<TenantSummary> assignedTenants, List<String> capabilities) {
    }
}
