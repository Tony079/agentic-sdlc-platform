package io.github.tony079.sdlc.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.common.api.RateLimitedException;
import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session login and logout for tenant users. */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnWebApplication
class AuthController {

    private final TenantUserAuthenticator authenticator;
    private final LoginThrottle throttle;
    private final SecurityEventRecorder securityEvents;
    private final SessionAuditService sessionAudit;
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    private final ClientMfaService mfa;
    private final boolean orgAdminMfaRequired;

    AuthController(TenantUserAuthenticator authenticator, LoginThrottle throttle, SecurityEventRecorder securityEvents,
                   SessionAuditService sessionAudit, SecurityContextRepository contextRepository,
                   CsrfTokenRepository csrfTokenRepository, ClientMfaService mfa,
                   @org.springframework.beans.factory.annotation.Value("${sdlc.security.org-admin-mfa-required:true}")
                   boolean orgAdminMfaRequired) {
        this.mfa = mfa;
        this.orgAdminMfaRequired = orgAdminMfaRequired;
        this.authenticator = authenticator;
        this.throttle = throttle;
        this.securityEvents = securityEvents;
        this.sessionAudit = sessionAudit;
        this.contextRepository = contextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    /** Issues the CSRF token (also set as the XSRF-TOKEN cookie). Clients send it back in X-CSRF-Token. */
    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    Object login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String accountKey = sha256(body.organisation().trim().toLowerCase(Locale.ROOT) + "|"
                + body.email().trim().toLowerCase(Locale.ROOT));
        String clientKey = request.getRemoteAddr();

        Optional<java.time.Duration> blocked = throttle.blockedFor(accountKey, clientKey);
        if (blocked.isPresent()) {
            securityEvents.record(SecurityEventType.LOGIN_THROTTLED, Severity.MEDIUM, accountKey, null);
            throw new RateLimitedException(blocked.get());
        }

        Optional<CurrentUser> authenticated = authenticator.authenticate(body.organisation(), body.email(), body.password());
        if (authenticated.isEmpty()) {
            throttle.recordFailure(accountKey, clientKey);
            securityEvents.record(SecurityEventType.LOGIN_FAILED, Severity.LOW, accountKey, null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "The organisation, email or password is incorrect.");
        }
        throttle.recordSuccess(accountKey);
        CurrentUser user = authenticated.get();

        // GD-08: an Organisation Admin completes sign-in only after TOTP (enrolling it the first time).
        if (user.orgAdmin() && orgAdminMfaRequired) {
            boolean enrolled = mfa.enrolled(user.tenantId(), user.userId());
            establish(new ClientMfaPending(user, enrolled), List.of(ClientMfaPending.AUTHORITY), request, response);
            return new MfaStatus(enrolled ? "MFA_REQUIRED" : "MFA_ENROLMENT_REQUIRED");
        }
        return complete(user, request, response);
    }

    /** Starts TOTP enrolment for a pending Organisation Admin; the secret is shown once. */
    @PostMapping("/mfa/enrolment")
    EnrolmentResponse startEnrolment() {
        ClientMfaPending pending = pending();
        if (pending.enrolled()) {
            throw ApiException.conflict("MFA_ALREADY_ENROLLED", "MFA is already enrolled.");
        }
        byte[] secret = mfa.startEnrolment(pending.user().tenantId(), pending.user().userId());
        String base32 = Totp.base32(secret);
        String label = java.net.URLEncoder.encode("Garuda:" + pending.user().email(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return new EnrolmentResponse(base32, "otpauth://totp/" + label + "?secret=" + base32
                + "&issuer=Garuda&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.STEP_SECONDS);
    }

    @PostMapping("/mfa/enrolment/confirm")
    MeResponse confirmEnrolment(@Valid @RequestBody CodeRequest body, HttpServletRequest request,
                                HttpServletResponse response) {
        ClientMfaPending pending = pending();
        throttleMfa(pending, request);
        if (pending.enrolled() || !mfa.confirmEnrolment(pending.user().tenantId(), pending.user().userId(), body.code())) {
            throw mfaFailed(pending, request);
        }
        return complete(pending.user(), request, response);
    }

    @PostMapping("/mfa/verify")
    MeResponse verify(@Valid @RequestBody CodeRequest body, HttpServletRequest request, HttpServletResponse response) {
        ClientMfaPending pending = pending();
        throttleMfa(pending, request);
        if (!pending.enrolled() || !mfa.verify(pending.user().tenantId(), pending.user().userId(), body.code())) {
            throw mfaFailed(pending, request);
        }
        return complete(pending.user(), request, response);
    }

    private MeResponse complete(CurrentUser user, HttpServletRequest request, HttpServletResponse response) {
        establish(user, user.authorities(), request, response);
        sessionAudit.recordLogin();
        return MeResponse.of(user);
    }

    private void establish(Object principal, List<String> authorities, HttpServletRequest request,
                           HttpServletResponse response) {
        // Session fixation protection: never reuse a pre-login session id.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, authorities.stream().map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        // Rotate the CSRF token at the authentication boundary.
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
    }

    private static ClientMfaPending pending() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ClientMfaPending pending)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in again.");
        }
        return pending;
    }

    private void throttleMfa(ClientMfaPending pending, HttpServletRequest request) {
        Optional<java.time.Duration> blocked = throttle.blockedFor("client-mfa|" + pending.user().userId(),
                request.getRemoteAddr());
        if (blocked.isPresent()) {
            throw new RateLimitedException(blocked.get());
        }
    }

    private ApiException mfaFailed(ClientMfaPending pending, HttpServletRequest request) {
        throttle.recordFailure("client-mfa|" + pending.user().userId(), request.getRemoteAddr());
        securityEvents.record(SecurityEventType.MFA_FAILED, Severity.MEDIUM, pending.user().userId().toString(), null);
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CODE", "The code is not valid. Wait for a new code.");
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessionAudit.recordLogout();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfTokenRepository.saveToken(null, request, response);
        return ResponseEntity.noContent().build();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record LoginRequest(@NotBlank @Size(max = 63) String organisation,
                        @NotBlank @Size(max = 320) String email,
                        @NotBlank @Size(max = 200) String password) {
    }

    record CsrfResponse(String headerName, String token) {
    }

    record MfaStatus(String status) {
    }

    record EnrolmentResponse(String secret, String otpauthUri) {
    }

    record CodeRequest(@NotBlank @Size(max = 6) String code) {
    }
}
