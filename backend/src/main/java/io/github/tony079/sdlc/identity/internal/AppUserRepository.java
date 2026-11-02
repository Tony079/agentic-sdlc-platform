package io.github.tony079.sdlc.identity.internal;

import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.CurrentUser.TenantRole;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Tenant users. Every query runs under RLS, so only the current tenant's users are visible. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class AppUserRepository {

    private final JdbcClient jdbc;

    AppUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UserRecord> findByEmail(String email) {
        return jdbc.sql("""
                SELECT id, tenant_id, email, display_name, password_hash, role, status
                FROM app.app_user WHERE lower(email) = lower(:email)
                """)
                .param("email", email)
                .query((rs, n) -> new UserRecord(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("password_hash"),
                        TenantRole.valueOf(rs.getString("role")), rs.getString("status")))
                .optional();
    }

    record UserRecord(UUID id, UUID tenantId, String email, String displayName, String passwordHash,
                      TenantRole role, String status) {

        boolean active() {
            return "ACTIVE".equals(status);
        }
    }
}
