package io.github.tony079.sdlc.retention.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.common.tx.TenantAwareTransactionManager;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.modelgateway.internal.FakeModelProvider;
import io.github.tony079.sdlc.storage.api.ObjectStore;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Retention and deletion (PRD FR-RET-01..16, AC-CORE-16, AC-EXT-30, AC-EXT-39) run as the sdlc_retention login against
 * a requirement driven end to end (fake GitHub, fake model, TEST ONLY). Closure times are back-dated as the test
 * superuser; nothing in the product can do that.
 */
class RetentionIT extends ApiIntegrationTest {

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();
    private FakeModelProvider fake;
    private ObjectStore storage;

    @BeforeEach
    void setUp() {
        WorkerContext.get();
        storage = WorkerContext.bean(ObjectStore.class);
        fake = WorkerContext.bean(FakeModelProvider.class);
        fake.reset();
        fake.cannedReply("ba.phase-a", """
                {"understanding": "Repayment metadata on the loan API.", "components": [],
                 "relevantPaths": ["%s"]}""".formatted(AnalysisScenario.CONTROLLER));
        fake.cannedReply("ba.phase-b", """
                {"items": [
                  {"kind": "FACT", "section": "FACTS", "statement": "Loans are served by the controller mapped to /loans.",
                   "evidence": [{"type": "REPOSITORY", "path": "%1$s", "startLine": 3, "endLine": 6,
                                 "documentVersionId": null, "chunk": null, "quote": "RequestMapping(\\"/loans\\")"}],
                   "risk": null, "component": "Loan API"}
                 ], "unsupported": [], "clarifications": []}""".formatted(AnalysisScenario.CONTROLLER));
    }

    @AfterEach
    void tearDown() {
        fake.reset();
    }

    private RetentionService service() {
        DriverManagerDataSource ds = new DriverManagerDataSource(db.appUrl(), "sdlc_retention", db.retentionPassword());
        return new RetentionService(JdbcClient.create(ds), new TenantAwareTransactionManager(ds), storage,
                Map.of("SNAPSHOT", 30, "DOCUMENT", 90, "BRD", 365, "AUDIT", 365));
    }

    /** A requirement analysed with an attachment, then rejected by a Business Reviewer: closed (REJECTED). */
    private AnalysisScenario closedRequirement() {
        Enrolled registrar = AnalysisScenario.registrar();
        AnalysisScenario s = AnalysisScenario.prepare(this, loggedIn("acme", "alice@acme.test"),
                loggedIn("acme", "aaron@acme.test"), AnalysisScenario.staffSession(client(), registrar, "acme", 0),
                registrar, "The loan screen must show the repayment date.");
        s.submit();
        s.drive();
        assertThat(s.state()).isEqualTo("INTERNAL_REVIEW");
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("reviewer"), AnalysisScenario.PASSWORD);
        StaffFixtures.assign(member.email(), "acme");
        StaffFixtures.grant(member.email(), StaffCapability.BUSINESS_REVIEWER);
        ApiClient reviewer = AnalysisScenario.staffSession(client(), member, "acme", 0);
        String version = admin.queryForObject("""
                SELECT id::text FROM app.brd_version WHERE requirement_id = ?::uuid AND status = 'IN_REVIEW'
                """, String.class, s.requirementId);
        assertThat(reviewer.post("/api/v1/staff/brd-versions/" + version + "/reject",
                Map.of("reason", "Closed for the retention test.")).status()).isEqualTo(200);
        assertThat(s.state()).isEqualTo("REJECTED");
        return s;
    }

    /** TEST ONLY: moves the closing transition into the past (the product never rewrites history). */
    private void closedDaysAgo(AnalysisScenario s, int days) throws Exception {
        try (Connection c = db.adminConnection(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.executeUpdate("""
                    UPDATE app.workflow_transition t SET occurred_at = clock_timestamp() - interval '%d days'
                    FROM app.workflow_instance i
                    WHERE t.instance_id = i.id AND t.seq = i.last_seq AND i.requirement_id = '%s'
                    """.formatted(days, UUID.fromString(s.requirementId)));
        }
    }

    private String snapshotOf(AnalysisScenario s, String column) {
        return admin.queryForObject("""
                SELECT s.%s FROM app.repository_snapshot s
                JOIN app.requirement_repository rr ON rr.id = s.requirement_repository_id
                WHERE rr.requirement_id = ?::uuid
                """.formatted(column), String.class, s.requirementId);
    }

    private boolean stored(String key) {
        try (var in = storage.open(key)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void closedRequirementDataIsDeletedClassByClassWithAuditAndNoContent() throws Exception {
        AnalysisScenario s = closedRequirement();
        String snapshotKey = snapshotOf(s, "storage_key");
        String snapshotId = snapshotOf(s, "id::text");
        String docKey = admin.queryForObject("SELECT storage_key FROM app.document_version WHERE id = ?::uuid",
                String.class, s.documentVersionId());
        assertThat(stored(snapshotKey)).isTrue();
        assertThat(stored(docKey)).isTrue();
        String brd = admin.queryForObject("SELECT id::text FROM app.brd_version WHERE requirement_id = ?::uuid",
                String.class, s.requirementId);

        // Closed recently: nothing is due.
        RetentionService.Result none = service().applyAll();
        assertThat(snapshotOf(s, "status")).isNotEqualTo("DELETED");

        // 100 days after closure: snapshots (30) and documents (90) are due; the BRD (365) is not.
        closedDaysAgo(s, 100);
        service().applyAll();
        assertThat(snapshotOf(s, "status")).isEqualTo("DELETED");
        assertThat(stored(snapshotKey)).isFalse();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.repository_file WHERE snapshot_id = ?::uuid",
                Integer.class, snapshotId)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.code_symbol WHERE snapshot_id = ?::uuid",
                Integer.class, snapshotId)).isZero();
        assertThat(admin.queryForObject("SELECT state FROM app.document_version WHERE id = ?::uuid", String.class,
                s.documentVersionId())).isEqualTo("DELETED");
        assertThat(stored(docKey)).isFalse();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.document_chunk WHERE version_id = ?::uuid",
                Integer.class, s.documentVersionId())).isZero();
        assertThat(admin.queryForObject("SELECT status FROM app.brd_version WHERE id = ?::uuid", String.class, brd))
                .isEqualTo("WITHDRAWN");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.brd_item WHERE brd_version_id = ?::uuid",
                Integer.class, brd)).isPositive();

        // Idempotent: a second run deletes nothing more.
        int before = admin.queryForObject("SELECT count(*) FROM app.audit_event WHERE action = 'RETENTION_DELETED'",
                Integer.class);
        service().applyAll();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.audit_event WHERE action = 'RETENTION_DELETED'",
                Integer.class)).isEqualTo(before);

        // A per-tenant override (FR-RET-07/AC-EXT-39) brings the BRD period down to 50 days.
        service().setPolicy("acme", "BRD", 50);
        service().applyAll();
        assertThat(admin.queryForObject("SELECT status FROM app.brd_version WHERE id = ?::uuid", String.class, brd))
                .isEqualTo("PURGED");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.brd_item WHERE brd_version_id = ?::uuid",
                Integer.class, brd)).isZero();
        assertThat(admin.queryForObject("""
                SELECT p.proposal::text FROM app.ba_result_pending p JOIN app.brd_version v ON v.pending_result_id = p.id
                WHERE v.id = ?::uuid""", String.class, brd)).isEqualTo("{\"purged\": true}");
        assertThat(s.alice.get("/api/v1/requirements/" + s.requirementId + "/brd").status()).isEqualTo(404);
        assertThat(s.alice.get("/api/v1/requirements/" + s.requirementId).status()).isEqualTo(200);
        service().setPolicy("acme", "BRD", 365);

        // Each deletion is audited, without content (FR-RET-04).
        assertThat(admin.queryForList("""
                SELECT resource_type FROM app.audit_event WHERE action = 'RETENTION_DELETED' AND resource_id IN
                  (?::uuid, ?::uuid, ?::uuid)""", String.class, snapshotId, s.documentId, brd))
                .containsExactlyInAnyOrder("REPOSITORY_SNAPSHOT", "DOCUMENT", "BRD_VERSION");
        assertThat(none).isNotNull();
    }

    @Test
    void expiredAuditRowsAreDeletedOnlyByTheRetentionLogin() throws Exception {
        UUID acme = db.tenantId("acme");
        UUID old = admin.queryForObject("""
                INSERT INTO app.audit_event (tenant_id, occurred_at, actor_type, action, resource_type, result)
                VALUES (?, clock_timestamp() - interval '400 days', 'SYSTEM', 'RETENTION_PROBE', 'TENANT', 'SUCCESS')
                RETURNING id""", UUID.class, acme);
        UUID recent = admin.queryForObject("""
                INSERT INTO app.audit_event (tenant_id, actor_type, action, resource_type, result)
                VALUES (?, 'SYSTEM', 'RETENTION_PROBE', 'TENANT', 'SUCCESS') RETURNING id""", UUID.class, acme);

        // The application and worker logins cannot delete audit rows (FR-AUD-02, FR-RET-14, S-01).
        for (Connection c : new Connection[] {db.appConnection(), db.workerConnection()}) {
            try (c; Statement st = c.createStatement()) {
                assertThat(catchThrowable(() -> st.executeUpdate("DELETE FROM app.audit_event")))
                        .hasMessageContaining("permission denied");
            }
        }
        service().applyAll();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.audit_event WHERE id = ?", Integer.class, old)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.audit_event WHERE id = ?", Integer.class, recent))
                .isEqualTo(1);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM app.audit_event WHERE tenant_id = ? AND action = 'RETENTION_AUDIT_PURGED'",
                Integer.class, acme)).isPositive();
    }

    @Test
    void retentionLoginIsNarrowAndRespectsTenantIsolation() throws Exception {
        try (Connection c = db.retentionConnection(); Statement st = c.createStatement()) {
            // No content columns, no requirement text, no user data, no business writes.
            assertThat(catchThrowable(() -> st.executeQuery("SELECT * FROM app.requirement_revision")))
                    .hasMessageContaining("permission denied");
            assertThat(catchThrowable(() -> st.executeQuery("SELECT * FROM app.app_user")))
                    .hasMessageContaining("permission denied");
            assertThat(catchThrowable(() -> st.executeUpdate("DELETE FROM app.requirement")))
                    .hasMessageContaining("permission denied");
            assertThat(catchThrowable(() -> st.executeUpdate("DELETE FROM app.workflow_transition")))
                    .hasMessageContaining("permission denied");
            // Without a tenant context, RLS shows nothing.
            try (var rs = st.executeQuery("SELECT count(*) FROM app.audit_event")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
        assertThatThrownBy(() -> service().setPolicy("acme", "BRD", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().setPolicy("acme", "SECRETS", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionService(null, null, null, Map.of("SNAPSHOT", 30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tenantDeletionProcedureClosesTheTenantAndAuditsIt() {
        UUID tenant = WorkerContext.newTenant("closing");
        String slug = admin.queryForObject("SELECT slug FROM app.tenant WHERE id = ?", String.class, tenant);
        service().deleteTenantData(slug);
        assertThat(admin.queryForObject("SELECT status FROM app.tenant WHERE id = ?", String.class, tenant))
                .isEqualTo("CLOSED");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.audit_event WHERE tenant_id = ? AND action = 'TENANT_DATA_DELETED'
                """, Integer.class, tenant)).isEqualTo(1);
        assertThatThrownBy(() -> service().deleteTenantData("no-such-tenant"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
