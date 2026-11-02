package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Catalog checks of the least-privilege and RLS model (PRD FR-AUTH-02, 17, 20; FR-AUD-02; AC-EXT-36 database part).
 * These run against the real migrated schema.
 */
class SchemaSecurityIT {

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();

    @Test
    void everyTenantOwnedTableHasForcedRlsOneTenantPolicyAndNotNullTenantId() {
        List<String> tenantTables = admin.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
                WHERE n.nspname = 'app' AND c.relkind = 'r' ORDER BY 1
                """, String.class);
        assertThat(tenantTables).containsExactly("app_user", "audit_event", "project");

        for (String table : tenantTables) {
            Map<String, Object> rls = admin.queryForMap("""
                    SELECT c.relrowsecurity AS enabled, c.relforcerowsecurity AS forced, a.attnotnull AS notnull
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id'
                    WHERE n.nspname = 'app' AND c.relname = ?
                    """, table);
            assertThat(rls).as(table).containsEntry("enabled", true).containsEntry("forced", true)
                    .containsEntry("notnull", true);

            List<Map<String, Object>> policies = admin.queryForList(
                    "SELECT policyname, permissive, cmd, qual, with_check FROM pg_policies WHERE schemaname = 'app' AND tablename = ?",
                    table);
            assertThat(policies).as(table).hasSize(1);
            assertThat(policies.get(0)).containsEntry("policyname", "tenant_isolation")
                    .containsEntry("permissive", "PERMISSIVE").containsEntry("cmd", "ALL");
            assertThat((String) policies.get(0).get("qual")).contains("tenant_id = app.current_tenant()");
            assertThat((String) policies.get(0).get("with_check")).contains("tenant_id = app.current_tenant()");
        }
    }

    @Test
    void runtimeRoleIsNotPrivileged() {
        Map<String, Object> role = admin.queryForMap(
                "SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb FROM pg_roles WHERE rolname = 'sdlc_app'");
        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false)
                .containsEntry("rolcreaterole", false).containsEntry("rolcreatedb", false);

        Integer owned = admin.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname IN ('app', 'migration_meta') AND c.relowner <> 'sdlc_owner'::regrole
                """, Integer.class);
        assertThat(owned).as("objects not owned by sdlc_owner").isZero();

        Map<String, Object> privileges = admin.queryForMap("""
                SELECT has_schema_privilege('sdlc_app', 'app', 'CREATE') AS schema_create,
                       has_schema_privilege('sdlc_app', 'migration_meta', 'USAGE') AS meta_usage,
                       has_database_privilege('sdlc_app', current_database(), 'CREATE') AS db_create,
                       has_database_privilege('sdlc_app', current_database(), 'TEMP') AS db_temp,
                       pg_has_role('sdlc_app', 'sdlc_owner', 'MEMBER') AS owner_member
                """);
        assertThat(privileges).containsEntry("schema_create", false).containsEntry("meta_usage", false)
                .containsEntry("db_create", false).containsEntry("db_temp", false)
                .containsEntry("owner_member", false);
    }

    @Test
    void auditTrailIsAppendOnlyForRuntimeRole() throws SQLException {
        Map<String, Object> grants = admin.queryForMap("""
                SELECT has_table_privilege('sdlc_app', 'app.audit_event', 'UPDATE') AS can_update,
                       has_table_privilege('sdlc_app', 'app.audit_event', 'DELETE') AS can_delete,
                       has_table_privilege('sdlc_app', 'app.audit_event', 'TRUNCATE') AS can_truncate,
                       has_table_privilege('sdlc_app', 'app.audit_event', 'INSERT') AS can_insert
                """);
        assertThat(grants).containsEntry("can_update", false).containsEntry("can_delete", false)
                .containsEntry("can_truncate", false).containsEntry("can_insert", true);

        try (Connection app = db.appConnection(); Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.execute("UPDATE app.audit_event SET result = 'FAILED'"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("DELETE FROM app.audit_event"))
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void runtimeRoleCannotAssumeOwnerRole() {
        Properties props = new Properties();
        props.setProperty("user", db.appUser());
        props.setProperty("password", db.appPassword());
        props.setProperty("options", "-c role=sdlc_owner");
        assertThatThrownBy(() -> DriverManager.getConnection(db.appUrl(), props).close())
                .hasMessageContaining("permission denied to set role");
    }

    @Test
    void runtimeRoleCannotAlterSchema() throws SQLException {
        try (Connection app = db.appConnection(); Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.execute("CREATE TABLE app.x (id int)"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("ALTER TABLE app.project DISABLE ROW LEVEL SECURITY"))
                    .hasMessageContaining("must be owner");
            assertThatThrownBy(() -> statement.execute("CREATE TEMP TABLE t (id int)"))
                    .hasMessageContaining("permission denied");
        }
    }
}
