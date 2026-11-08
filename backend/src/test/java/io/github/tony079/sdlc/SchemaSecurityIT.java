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
        assertThat(tenantTables).containsExactly("analysis_input_set", "app_user", "approval", "audit_event",
                "ba_result_pending", "brd", "brd_flag_ack", "brd_item", "brd_item_evidence", "brd_version",
                "build_dependency", "clarification", "clarification_message", "client_change_request", "code_symbol",
                "document", "document_chunk", "document_decision",
                "document_extraction", "document_version",
                "idempotency_record", "job", "job_attempt", "model_call_record", "notification",
                "outbox_event", "processed_event", "project", "project_repository", "repository",
                "repository_file", "repository_link_request", "repository_snapshot", "requirement",
                "requirement_closure", "requirement_key_counter", "requirement_repository", "requirement_repository_link", "requirement_revision", "retention_policy", "stage_execution",
                "workflow_instance", "workflow_transition");

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
        for (String login : List.of("sdlc_app", "sdlc_worker")) {
            Map<String, Object> role = admin.queryForMap(
                    "SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb FROM pg_roles WHERE rolname = ?", login);
            assertThat(role).as(login).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false)
                    .containsEntry("rolcreaterole", false).containsEntry("rolcreatedb", false);
            Map<String, Object> rights = admin.queryForMap("""
                    SELECT has_schema_privilege(?, 'app', 'CREATE') AS schema_create,
                           has_database_privilege(?, current_database(), 'TEMP') AS db_temp,
                           pg_has_role(?, 'sdlc_owner', 'MEMBER') AS owner_member
                    """, login, login, login);
            assertThat(rights).as(login).containsEntry("schema_create", false).containsEntry("db_temp", false)
                    .containsEntry("owner_member", false);
        }

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

    /** Revision and transition history is append-only for the runtime role; no Milestone 2 table allows DELETE. */
    @Test
    void historyTablesAreAppendOnlyAndNothingIsDeletable() {
        for (String table : List.of("requirement_revision", "workflow_transition", "idempotency_record")) {
            Map<String, Object> grants = admin.queryForMap("""
                    SELECT has_table_privilege('sdlc_app', ?, 'INSERT') AS can_insert,
                           has_any_column_privilege('sdlc_app', ?, 'UPDATE') AS can_update,
                           has_table_privilege('sdlc_app', ?, 'TRUNCATE') AS can_truncate
                    """, "app." + table, "app." + table, "app." + table);
            assertThat(grants).as(table).containsEntry("can_insert", true).containsEntry("can_update", false)
                    .containsEntry("can_truncate", false);
        }
        for (String table : List.of("requirement_key_counter", "requirement", "requirement_revision",
                "workflow_instance", "workflow_transition", "idempotency_record")) {
            Boolean canDelete = admin.queryForObject("SELECT has_table_privilege('sdlc_app', ?, 'DELETE')",
                    Boolean.class, "app." + table);
            assertThat(canDelete).as(table + " DELETE").isFalse();
        }
        // Mutable rows expose only the columns the application must change.
        List<String> requirementColumns = admin.queryForList("""
                SELECT column_name FROM information_schema.column_privileges
                WHERE grantee = 'sdlc_app' AND table_schema = 'app' AND table_name = 'requirement'
                  AND privilege_type = 'UPDATE' ORDER BY column_name
                """, String.class);
        assertThat(requirementColumns).containsExactly("current_revision_no", "updated_at", "version");
        List<String> instanceColumns = admin.queryForList("""
                SELECT column_name FROM information_schema.column_privileges
                WHERE grantee = 'sdlc_app' AND table_schema = 'app' AND table_name = 'workflow_instance'
                  AND privilege_type = 'UPDATE' ORDER BY column_name
                """, String.class);
        assertThat(instanceColumns).containsExactly("last_seq", "projected_state", "updated_at", "version");
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

    /**
     * Milestone 3 boundary (decision Q-M3-3): the API login may only produce outbox events; delivery state, consumer
     * idempotency records and the job queue belong to the worker login, which itself has no DELETE anywhere and no
     * access to requirement content, users or sessions.
     */
    @Test
    void apiAndWorkerLoginsHaveSeparateNarrowPrivileges() {
        assertThat(columns("sdlc_app", "outbox_event", "INSERT"))
                .containsExactly("aggregate_id", "aggregate_type", "event_type", "payload", "tenant_id");
        assertThat(columns("sdlc_app", "outbox_event", "UPDATE")).isEmpty();
        assertThat(columns("sdlc_app", "outbox_event", "SELECT")).isEmpty();
        for (String table : List.of("processed_event", "job", "job_attempt")) {
            String name = "app." + table;
            assertThat(admin.queryForObject("""
                    SELECT has_any_column_privilege('sdlc_app', ?, 'SELECT') OR has_any_column_privilege('sdlc_app', ?, 'INSERT')
                        OR has_any_column_privilege('sdlc_app', ?, 'UPDATE') OR has_table_privilege('sdlc_app', ?, 'DELETE')
                    """, Boolean.class, name, name, name, name)).as("sdlc_app on " + table).isFalse();
        }

        assertThat(columns("sdlc_worker", "outbox_event", "UPDATE"))
                .containsExactly("attempts", "dead_at", "last_error_code", "next_attempt_at", "published_at");
        assertThat(columns("sdlc_worker", "job", "UPDATE")).doesNotContain("max_attempts", "max_infra_failures",
                "lease_seconds", "max_runtime_seconds", "backoff_base_seconds", "backoff_cap_seconds", "tenant_id",
                "job_type", "payload", "idempotency_key");
        assertThat(columns("sdlc_worker", "workflow_instance", "UPDATE"))
                .containsExactly("last_seq", "projected_state", "updated_at", "version");
        assertThat(admin.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'app' AND c.relkind = 'r'
                  AND (has_table_privilege('sdlc_worker', c.oid, 'DELETE') OR has_table_privilege('sdlc_worker', c.oid, 'TRUNCATE')
                       OR has_table_privilege('sdlc_app', c.oid, 'TRUNCATE'))
                """, String.class)).as("DELETE/TRUNCATE").isEmpty();
        for (String table : List.of("requirement", "requirement_revision", "app_user", "spring_session", "project",
                "idempotency_record", "job_type", "workflow_definition")) {
            String name = "app." + table;
            assertThat(admin.queryForObject(
                    "SELECT has_any_column_privilege('sdlc_worker', ?, 'INSERT') OR has_any_column_privilege('sdlc_worker', ?, 'UPDATE')",
                    Boolean.class, name, name)).as("worker writes " + table).isFalse();
        }
        for (String table : List.of("app_user", "spring_session", "project")) {
            assertThat(admin.queryForObject("SELECT has_any_column_privilege('sdlc_worker', ?, 'SELECT')", Boolean.class,
                    "app." + table)).as("worker reads " + table).isFalse();
        }
        // R1-M5 (TDD 12.3): the BA agent analyses the requirement revision, so the worker reads it (read-only) and five
        // identity columns of requirement; it still cannot write either (checked above).
        assertThat(columns("sdlc_worker", "requirement", "SELECT"))
                .containsExactly("created_by", "current_revision_no", "id", "project_id", "tenant_id");
        assertThat(columns("sdlc_worker", "tenant", "SELECT")).containsExactly("id", "status");
    }

    @Test
    void apiLoginCannotMarkEventsPublishedOrWriteProcessedEvents() throws SQLException {
        String tenant = db.tenantId("acme").toString();
        try (Connection app = db.appConnection(); Statement statement = app.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE app.outbox_event SET published_at = now()"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE app.outbox_event SET dead_at = now()"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO app.outbox_event (tenant_id, aggregate_type, "
                    + "aggregate_id, event_type, payload, published_at) VALUES ('" + tenant + "', 'REQUIREMENT', "
                    + "gen_random_uuid(), 'RequirementSubmitted', '{\"v\":1}', now())"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO app.processed_event (tenant_id, event_id, consumer) "
                    + "VALUES ('" + tenant + "', gen_random_uuid(), 'workflow-routing')"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM app.outbox_event"))
                    .hasMessageContaining("permission denied");
        }
        try (Connection worker = db.workerConnection(); Statement statement = worker.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM app.app_user"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE app.requirement_revision SET title = 'x'"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM app.outbox_event"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE app.outbox_event SET payload = '{\"v\":2}'"))
                    .hasMessageContaining("permission denied");
        }
    }

    /** No privileged functions exist (decision Q-M3-2); PL/pgSQL functions pin their search_path. */
    @Test
    void noSecurityDefinerFunctionsAndNoPublicExecute() {
        assertThat(admin.queryForList("""
                SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'app' AND p.prosecdef
                """, String.class)).isEmpty();
        assertThat(admin.queryForList("""
                SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'app' AND has_function_privilege('public', p.oid, 'EXECUTE')
                """, String.class)).isEmpty();
        assertThat(admin.queryForList("""
                SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'app' AND p.prolang = (SELECT oid FROM pg_language WHERE lanname = 'plpgsql')
                  AND NOT coalesce(array_to_string(p.proconfig, ',') LIKE '%search_path=pg_catalog, app%', false)
                """, String.class)).isEmpty();
    }

    private List<String> columns(String role, String table, String privilege) {
        return admin.queryForList("""
                SELECT a.attname::text FROM pg_attribute a
                WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped
                  AND has_column_privilege(?, a.attrelid, a.attnum, ?)
                ORDER BY a.attname
                """, String.class, "app." + table, role, privilege);
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
