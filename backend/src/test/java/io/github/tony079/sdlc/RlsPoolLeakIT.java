package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import io.github.tony079.sdlc.common.tx.TenantAwareTransactionManager;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pooled connections never carry a previous tenant's context (PRD FR-AUTH-21; TDD RLS-POOL-01).
 * A pool of exactly one connection forces every transaction onto the same physical connection, which the test
 * confirms by recording the PostgreSQL backend pid inside each transaction.
 */
class RlsPoolLeakIT {

    @Test
    void tenantContextDoesNotLeakAcrossTransactionsOnOnePooledConnection() {
        TestDatabase db = TestDatabase.get();
        UUID acme = db.tenantId("acme");
        UUID globex = db.tenantId("globex");
        Set<Integer> backendPids = new HashSet<>();
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setJdbcUrl(db.appUrl());
            pool.setUsername(db.appUser());
            pool.setPassword(db.appPassword());
            pool.setMaximumPoolSize(1);
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            TransactionTemplate tx = new TransactionTemplate(new TenantAwareTransactionManager(pool));

            for (int round = 0; round < 3; round++) {
                List<UUID> a = TenantContext.callAs(acme, () -> tx.execute(s -> {
                    backendPids.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return jdbc.queryForList("SELECT DISTINCT tenant_id FROM app.app_user", UUID.class);
                }));
                assertThat(a).containsExactly(acme);

                // Outside any transaction, on the same physical connection, the transaction-local setting is gone.
                assertThat(jdbc.queryForObject("SELECT coalesce(current_setting('app.tenant_id', true), '')", String.class))
                        .isEmpty();

                List<UUID> b = TenantContext.callAs(globex, () -> tx.execute(s -> {
                    backendPids.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return jdbc.queryForList("SELECT DISTINCT tenant_id FROM app.app_user", UUID.class);
                }));
                assertThat(b).containsExactly(globex);

                // No tenant: zero rows, no error (fail closed).
                Integer none = tx.execute(s -> {
                    backendPids.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return jdbc.queryForObject("SELECT count(*) FROM app.app_user", Integer.class);
                });
                assertThat(none).isZero();
            }
        }
        assertThat(backendPids).as("all transactions used one physical connection").hasSize(1);
    }
}
