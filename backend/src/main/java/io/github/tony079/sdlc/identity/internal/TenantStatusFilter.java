package io.github.tony079.sdlc.identity.internal;

import java.io.IOException;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.CurrentUser;

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
 * Immediate access revocation for tenant soft deletion (O-25): every request of a client session re-reads its tenant's
 * status (the tenant table is global, read without tenant scope). Anything but ACTIVE invalidates the session and
 * answers 401 before any controller runs. Logins of such a tenant are already refused by the authenticator.
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
            String status;
            try {
                status = readOnlyTx.execute(s -> jdbc.sql("SELECT status FROM app.tenant WHERE id = :id")
                        .param("id", tenant).query(String.class).optional().orElse(null));
            } finally {
                SecurityContext restored = SecurityContextHolder.createEmptyContext();
                restored.setAuthentication(authentication);
                SecurityContextHolder.setContext(restored);
            }
            if (!"ACTIVE".equals(status)) {
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
