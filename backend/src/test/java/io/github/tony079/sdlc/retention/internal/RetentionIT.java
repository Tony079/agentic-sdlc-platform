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
                Map.of("SNAPSHOT", 30, "EXTRACTED_TEXT", 30, "DOCUMENT", 365, "BRD", 365, "AUDIT", 365));
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
        s.drive();
        // O-25: closure is recorded by the worker once nothing is left open.
        WorkerContext.bean(io.github.tony079.sdlc.workflow.internal.ClosureRecorder.class).recordEligible();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.requirement_closure WHERE requirement_id = ?::uuid",
                Integer.class, s.requirementId)).isEqualTo(1);
        return s;
    }

    /** TEST ONLY: moves the recorded closure into the past (the product never rewrites it). */
    private void closedDaysAgo(AnalysisScenario s, int days) throws Exception {
        try (Connection c = db.adminConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    UPDATE app.requirement_closure SET terminal_at = clock_timestamp() - interval '%1$d days',
                           closed_at = clock_timestamp() - interval '%1$d days'
                    WHERE requirement_id = '%2$s'
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

        // 100 days after closure: snapshots and extracted text (30) are due; originals and the BRD (365) are not.
        closedDaysAgo(s, 100);
        service().applyAll();
        assertThat(snapshotOf(s, "status")).isEqualTo("DELETED");
        assertThat(stored(snapshotKey)).isFalse();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.repository_file WHERE snapshot_id = ?::uuid",
                Integer.class, snapshotId)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.code_symbol WHERE snapshot_id = ?::uuid",
                Integer.class, snapshotId)).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.document_chunk WHERE version_id = ?::uuid",
                Integer.class, s.documentVersionId())).isZero();
        assertThat(admin.queryForObject("SELECT state FROM app.document_version WHERE id = ?::uuid", String.class,
                s.documentVersionId())).isNotEqualTo("DELETED");
        assertThat(stored(docKey)).isTrue();
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

        // Per-tenant overrides (FR-RET-07/AC-EXT-39) bring the original-document and BRD periods down to 50 days.
        service().setPolicy("acme", "DOCUMENT", 50);
        service().setPolicy("acme", "BRD", 50);
        service().applyAll();
        assertThat(admin.queryForObject("SELECT state FROM app.document_version WHERE id = ?::uuid", String.class,
                s.documentVersionId())).isEqualTo("DELETED");
        assertThat(stored(docKey)).isFalse();
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
        service().setPolicy("acme", "DOCUMENT", 365);

        // Each deletion is audited, without content (FR-RET-04).
        assertThat(admin.queryForList("""
                SELECT resource_type FROM app.audit_event WHERE action = 'RETENTION_DELETED' AND resource_id IN
                  (?::uuid, ?::uuid, ?::uuid, ?::uuid)""", String.class, snapshotId, s.documentId, brd,
                s.documentVersionId())).containsExactlyInAnyOrder("REPOSITORY_SNAPSHOT", "EXTRACTED_TEXT", "DOCUMENT",
                "BRD_VERSION");
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
            // Tenant deletion is not a retention operation any more (O-25 soft deletion is two-person, API only).
            assertThat(catchThrowable(() -> st.executeUpdate("UPDATE app.tenant SET status = 'DELETED'")))
                    .hasMessageContaining("permission denied");
            assertThat(catchThrowable(() -> st.executeUpdate("UPDATE app.requirement_closure SET closed_at = now()")))
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
    void softDeletedTenantsAreNeverPurgedByRetention() {
        UUID tenant = WorkerContext.newTenant("frozen");
        admin.update("""
                INSERT INTO app.audit_event (tenant_id, occurred_at, actor_type, action, resource_type, result)
                VALUES (?, clock_timestamp() - interval '900 days', 'SYSTEM', 'RETENTION_PROBE', 'TENANT', 'SUCCESS')
                """, tenant);
        admin.update("UPDATE app.tenant SET status = 'DELETED' WHERE id = ?", tenant);
        service().applyAll();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.audit_event WHERE tenant_id = ?", Integer.class,
                tenant)).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT status FROM app.tenant WHERE id = ?", String.class, tenant))
                .isEqualTo("DELETED");
    }

    @Test
    void requirementsWithoutAClosureRecordAreNotTouched() throws Exception {
        UUID acme = db.tenantId("acme");
        int closures = admin.queryForObject("SELECT count(*) FROM app.requirement_closure WHERE tenant_id = ?",
                Integer.class, acme);
        int openSnapshots = admin.queryForObject("""
                SELECT count(*) FROM app.repository_snapshot s JOIN app.requirement_repository rr
                  ON rr.id = s.requirement_repository_id
                WHERE s.status <> 'DELETED' AND NOT EXISTS (SELECT 1 FROM app.requirement_closure c
                                                            WHERE c.requirement_id = rr.requirement_id)
                """, Integer.class);
        service().applyAll();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.repository_snapshot s JOIN app.requirement_repository rr
                  ON rr.id = s.requirement_repository_id
                WHERE s.status <> 'DELETED' AND NOT EXISTS (SELECT 1 FROM app.requirement_closure c
                                                            WHERE c.requirement_id = rr.requirement_id)
                """, Integer.class)).isEqualTo(openSnapshots);
        assertThat(closures).isGreaterThanOrEqualTo(0);
    }
}
