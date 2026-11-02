package io.github.tony079.sdlc.tenancy.internal;

import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.tenancy.api.TenantLookup;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcTenantLookup implements TenantLookup {

    private final JdbcClient jdbc;

    JdbcTenantLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TenantRef> findActiveBySlug(String slug) {
        return jdbc.sql("SELECT id, slug, name FROM app.tenant WHERE slug = :slug AND status = 'ACTIVE'")
                .param("slug", slug)
                .query((rs, n) -> new TenantRef(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name")))
                .optional();
    }
}
