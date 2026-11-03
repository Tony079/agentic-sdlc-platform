package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import io.github.tony079.sdlc.common.tx.TenantAwareTransactionManager;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database-level tenant isolation through the application's transaction manager, connected as sdlc_app
 * (PRD FR-AUTH-17/18, AC-EXT-36 database part).
 */
class RlsIsolationIT {

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static UUID acme;
    private static UUID globex;

    @BeforeAll
    static void setUp() {
        TestDatabase db = TestDatabase.get();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(db.appUrl());
        dataSource.setUsername(db.appUser());
        dataSource.setPassword(db.appPassword());
        dataSource.setMaximumPoolSize(2);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new TenantAwareTransactionManager(dataSource));
        acme = db.tenantId("acme");
        globex = db.tenantId("globex");
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
    }

    @Test
    void tenantSeesOnlyItsOwnRows() {
        List<UUID> acmeView = TenantContext.callAs(acme, () -> tx.execute(s ->
                jdbc.queryForList("SELECT DISTINCT tenant_id FROM app.app_user", UUID.class)));
        List<UUID> globexView = TenantContext.callAs(globex, () -> tx.execute(s ->
                jdbc.queryForList("SELECT DISTINCT tenant_id FROM app.app_user", UUID.class)));
        assertThat(acmeView).containsExactly(acme);
        assertThat(globexView).containsExactly(globex);
    }

    @Test
    void noTenantContextSeesNothingAndDoesNotError() {
        Integer users = tx.execute(s -> jdbc.queryForObject("SELECT count(*) FROM app.app_user", Integer.class));
        assertThat(users).isZero();
    }

    @Test
    void cannotWriteRowsForAnotherTenant() {
        UUID acmeUser = TenantContext.callAs(acme, () -> tx.execute(s ->
                jdbc.queryForObject("SELECT id FROM app.app_user LIMIT 1", UUID.class)));
        assertThatThrownBy(() -> TenantContext.callAs(acme, () -> tx.execute(s ->
                jdbc.update("INSERT INTO app.project (tenant_id, name, created_by) VALUES (?, ?, ?)",
                        globex, "cross-" + UUID.randomUUID(), acmeUser))))
                .rootCause().hasMessageContaining("new row violates row-level security policy for table \"project\"");
    }

    @Test
    void auditRowCannotBeWrittenIntoAnotherTenant() {
        assertThatThrownBy(() -> TenantContext.callAs(acme, () -> tx.execute(s ->
                jdbc.update("INSERT INTO app.audit_event (tenant_id, actor_type, action, resource_type, result) "
                        + "VALUES (?, 'SYSTEM', 'LOGIN', 'USER', 'SUCCESS')", globex))))
                .rootCause().hasMessageContaining("new row violates row-level security policy for table \"audit_event\"");
    }
}
