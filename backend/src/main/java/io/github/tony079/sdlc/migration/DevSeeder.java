package io.github.tony079.sdlc.migration;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Development/demo data (decision D-M1-E). Runs only in the migrate process, only when explicitly enabled,
 * and only with a password supplied from the environment. Idempotent. Password hashes are computed here
 * and never committed. Never enable outside local development and CI.
 */
@Component
@Profile("migrate")
@ConditionalOnProperty(name = "sdlc.dev-seed.enabled", havingValue = "true")
class DevSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

    record SeedUser(String email, String displayName, String role) {
    }

    record SeedTenant(String slug, String name, List<SeedUser> users) {
    }

    static final List<SeedTenant> TENANTS = List.of(
            new SeedTenant("acme", "Acme Corporation", List.of(
                    new SeedUser("alice@acme.test", "Alice Client", "MEMBER"),
                    new SeedUser("aaron@acme.test", "Aaron Approver", "ORG_ADMIN"))),
            new SeedTenant("globex", "Globex Limited", List.of(
                    new SeedUser("gina@globex.test", "Gina Client", "MEMBER"),
                    new SeedUser("gary@globex.test", "Gary Approver", "ORG_ADMIN"))));

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final String password;

    DevSeeder(JdbcClient jdbc, PasswordEncoder passwordEncoder, PlatformTransactionManager transactionManager,
              @Value("${sdlc.dev-seed.password:}") String password) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.tx = new TransactionTemplate(transactionManager);
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("SDLC_DEV_SEED_PASSWORD must be set (at least 12 characters) to seed data");
        }
        for (SeedTenant tenant : TENANTS) {
            UUID tenantId = tx.execute(status -> {
                jdbc.sql("INSERT INTO app.tenant (slug, name, status) VALUES (:slug, :name, 'ACTIVE') ON CONFLICT (slug) DO NOTHING")
                        .param("slug", tenant.slug()).param("name", tenant.name()).update();
                return jdbc.sql("SELECT id FROM app.tenant WHERE slug = :slug").param("slug", tenant.slug())
                        .query(UUID.class).single();
            });
            // app_user is under forced RLS (also for the owner), so inserts run inside the tenant's context.
            TenantContext.callAs(tenantId, () -> tx.execute(status -> {
                for (SeedUser user : tenant.users()) {
                    jdbc.sql("""
                            INSERT INTO app.app_user (tenant_id, email, display_name, password_hash, role, status)
                            VALUES (:tenant, :email, :name, :hash, :role, 'ACTIVE')
                            ON CONFLICT DO NOTHING
                            """)
                            .param("tenant", tenantId).param("email", user.email()).param("name", user.displayName())
                            .param("hash", passwordEncoder.encode(password)).param("role", user.role())
                            .update();
                }
                return null;
            }));
        }
        log.info("Development seed applied: {} tenants", TENANTS.size());
    }
}
