package io.github.tony079.sdlc.identity.internal;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.StaffPrincipal;
import io.github.tony079.sdlc.identity.internal.StaffRepository.StaffRecord;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-validates every staff session against the database before authorisation (O-06): a DISABLED staff member's session
 * is invalidated; an active tenant whose assignment was revoked is cleared. Runs inside the security filter chain
 * after the security context is loaded. The check runs with no tenant scope (staff tables are global).
 */
class StaffSessionFilter extends OncePerRequestFilter {

    private final StaffRepository staff;
    private final SecurityContextRepository contextRepository;
    private final TransactionTemplate readOnlyTx;

    StaffSessionFilter(StaffRepository staff, SecurityContextRepository contextRepository,
                       PlatformTransactionManager transactionManager) {
        this.staff = staff;
        this.contextRepository = contextRepository;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        UUID staffId = principal instanceof StaffPrincipal p ? p.staffId()
                : principal instanceof StaffMfaPending p ? p.staffId() : null;
        if (staffId != null) {
            // Evaluate with an empty context so the transaction carries no tenant scope.
            SecurityContextHolder.clearContext();
            Optional<StaffRecord> record;
            boolean assigned;
            try {
                record = readOnlyTx.execute(status -> staff.findById(staffId));
                assigned = principal instanceof StaffPrincipal p && p.activeTenantId() != null
                        && Boolean.TRUE.equals(readOnlyTx.execute(status -> staff.assigned(staffId, p.activeTenantId())));
            } finally {
                SecurityContext restored = SecurityContextHolder.createEmptyContext();
                restored.setAuthentication(authentication);
                SecurityContextHolder.setContext(restored);
            }
            if (record == null || record.isEmpty() || !record.get().active()) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
                // Answer here: the rest of the chain must not look up the invalidated session again.
                SecurityConfig.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                        "Authentication is required.");
                return;
            } else if (principal instanceof StaffPrincipal p && p.activeTenantId() != null && !assigned) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        p.withActiveTenant(null, null), null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
                SecurityContextHolder.setContext(context);
                contextRepository.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
