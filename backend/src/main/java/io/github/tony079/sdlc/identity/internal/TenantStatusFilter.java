package io.github.tony079.sdlc.identity.internal;

import java.io.IOException;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Immediate access revocation: every request of a client session re-reads its organisation's status (soft deletion
 * O-25, suspension FR-ORG-03) and the user's own status and organisation role (deactivation and role changes by an
 * Organisation Admin, FR-ORG-06). An organisation that is not ACTIVE, a user who is not ACTIVE, or a role different
 * from the one signed in with invalidates the session and answers 401 before any controller runs.
 */
class TenantStatusFilter extends OncePerRequestFilter {

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnlyTx;

    TenantStatusFilter(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            UUID tenant = user.tenantId();
            SecurityContextHolder.clearContext();
            boolean valid;
            try {
                String status = readOnlyTx.execute(s -> jdbc.sql("SELECT status FROM app.tenant WHERE id = :id")
                        .param("id", tenant).query(String.class).optional().orElse(null));
                // The user row is tenant-scoped (RLS): read it inside the user's own tenant.
                String current = "ACTIVE".equals(status) ? TenantContext.callAs(tenant, () -> readOnlyTx.execute(s ->
                        jdbc.sql("SELECT status || ':' || role FROM app.app_user WHERE id = :id")
                                .param("id", user.userId()).query(String.class).optional().orElse(null))) : null;
                valid = ("ACTIVE:" + user.role().name()).equals(current);
            } finally {
                SecurityContext restored = SecurityContextHolder.createEmptyContext();
                restored.setAuthentication(authentication);
                SecurityContextHolder.setContext(restored);
            }
            if (!valid) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
                SecurityConfig.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                        "Authentication is required.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
