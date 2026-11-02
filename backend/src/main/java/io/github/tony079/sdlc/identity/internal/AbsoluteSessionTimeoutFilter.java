package io.github.tony079.sdlc.identity.internal;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Invalidates sessions older than the absolute lifetime (TDD 22.2: 8 hours), regardless of activity.
 * Runs after the Spring Session repository filter and before Spring Security.
 */
class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private final Duration maxAge;

    AbsoluteSessionTimeoutFilter(Duration maxAge) {
        this.maxAge = maxAge;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && System.currentTimeMillis() - session.getCreationTime() > maxAge.toMillis()) {
            session.invalidate();
        }
        chain.doFilter(request, response);
    }
}
