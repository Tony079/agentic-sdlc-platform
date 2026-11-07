package io.github.tony079.sdlc.identity.internal;

import java.io.IOException;
import java.time.Duration;

import io.github.tony079.sdlc.common.api.CorrelationId;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * HTTP security for the API. Authorisation order (TDD 20.1): authenticated, role allowed for the endpoint,
 * tenant ownership via RLS. Everything not explicitly listed is denied.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication
class SecurityConfig {

    static final String CSRF_HEADER = "X-CSRF-Token";

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setHeaderName(CSRF_HEADER);
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Strict").secure(secure).path("/"));
        return repository;
    }

    @Bean
    FilterRegistrationBean<AbsoluteSessionTimeoutFilter> absoluteSessionTimeoutFilter(
            @Value("${sdlc.security.absolute-session-timeout}") Duration maxAge) {
        FilterRegistrationBean<AbsoluteSessionTimeoutFilter> registration =
                new FilterRegistrationBean<>(new AbsoluteSessionTimeoutFilter(maxAge));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 10);
        return registration;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityContextRepository contextRepository,
                                    CsrfTokenRepository csrfTokenRepository, StaffRepository staffRepository,
                                    PlatformTransactionManager transactionManager) throws Exception {
        http
                // Staff sessions are re-validated against the database on every request (O-06).
                .addFilterAfter(new StaffSessionFilter(staffRepository, contextRepository, transactionManager),
                        SecurityContextHolderFilter.class)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        // Plain handler: the SPA echoes the cookie value in the header.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .anonymous(anonymous -> { })
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // Internal staff (O-06, O-11): password first, then TOTP; nothing else before MFA.
                        .requestMatchers(HttpMethod.POST, "/api/v1/staff/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/staff/auth/logout")
                                .hasAnyRole("STAFF_MFA_PENDING", "STAFF")
                        .requestMatchers("/api/v1/staff/auth/mfa/**").hasRole("STAFF_MFA_PENDING")
                        .requestMatchers("/api/v1/staff/**").hasRole("STAFF")
                        // C-18: issue/revoke by the requirement owner, redeem by a Client Approver (checked in the service).
                        .requestMatchers("/api/v1/repository-link-requests/**")
                                .hasAnyRole("CLIENT_USER", "CLIENT_APPROVER")
                        .requestMatchers("/api/v1/projects", "/api/v1/projects/**",
                                        "/api/v1/requirements", "/api/v1/requirements/**")
                                .hasAnyRole("CLIENT_USER", "CLIENT_APPROVER")
                        .requestMatchers("/api/v1/me", "/api/v1/auth/logout")
                                .hasAnyRole("CLIENT_USER", "CLIENT_APPROVER")
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                                        "Authentication is required."))
                        .accessDeniedHandler((request, response, e) -> {
                            if (e instanceof CsrfException) {
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "CSRF_TOKEN_INVALID",
                                        "The CSRF token is missing or invalid.");
                            } else {
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Not allowed.");
                            }
                        }))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    /** Writes the error envelope. All values are fixed strings or a validated correlation id. */
    static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        String correlation = CorrelationId.current().filter(CorrelationId::isValid).orElse(null);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"correlationId\":" + (correlation == null ? "null" : "\"" + correlation + "\"") + "}}");
    }
}
