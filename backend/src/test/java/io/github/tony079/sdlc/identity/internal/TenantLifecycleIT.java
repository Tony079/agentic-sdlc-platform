package io.github.tony079.sdlc.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.queue.internal.DeletedTenantJobCanceller;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O-25 tenant soft deletion (owner decision 2026-11-08): Client Approver request, verification and a second approval by
 * different TENANT_DELETE holders, immediate access revocation, job cancellation, no physical deletion, restoration.
 */
class TenantLifecycleIT extends ApiIntegrationTest {

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();

    private record Org(UUID id, String slug, String approver, String user) {
    }

    /** A fresh tenant with one Client Approver and one Client User (same seed password as the test users). */
    private Org org() {
        UUID id = WorkerContext.newTenant("lifecycle");
        String slug = admin.queryForObject("SELECT slug FROM app.tenant WHERE id = ?", String.class, id);
        String hash = admin.queryForObject("SELECT password_hash FROM app.app_user WHERE email = 'alice@acme.test'",
                String.class);
        for (String[] u : new String[][] {{"approver@" + slug + ".test", "ORG_ADMIN"},
                {"user@" + slug + ".test", "MEMBER"}}) {
            admin.update("""
                    INSERT INTO app.app_user (tenant_id, email, display_name, password_hash, role, status)
                    VALUES (?, ?, 'Test', ?, ?, 'ACTIVE')
                    """, id, u[0], hash, u[1]);
        }
        return new Org(id, slug, "approver@" + slug + ".test", "user@" + slug + ".test");
    }

    private Enrolled deleter(String slug, boolean capability) {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("deleter"), AnalysisScenario.PASSWORD);
        StaffFixtures.assign(member.email(), slug);
        if (capability) {
            StaffFixtures.grant(member.email(), StaffCapability.TENANT_DELETE);
        }
        return member;
    }

    private ApiClient staff(Enrolled member, String slug) {
        return AnalysisScenario.staffSession(client(), member, slug, 0);
    }

    @Test
    void twoPersonSoftDeletionRevokesAccessStopsJobsAndKeepsAllData() throws Exception {
        Org org = org();
        ApiClient approver = loggedIn(org.slug(), org.approver());
        ApiClient user = loggedIn(org.slug(), org.user());
        Enrolled first = deleter(org.slug(), true);
        Enrolled second = deleter(org.slug(), true);
        Enrolled noCapability = deleter(org.slug(), false);
        // Assigned while the tenant is active; acts later on the deleted tenant without an active tenant.
        Enrolled third = deleter(org.slug(), true);
        ApiClient a = staff(first, org.slug());
        ApiClient b = staff(second, org.slug());
        ApiClient c = staff(noCapability, org.slug());

        // A Client User cannot request deletion; a short reason is refused.
        assertThat(user.post("/api/v1/tenant/deletion-requests", Map.of("reason", "Contract ended in 2026.")).status())
                .isEqualTo(403);
        assertThat(approver.post("/api/v1/tenant/deletion-requests", Map.of("reason", "short")).status())
                .isEqualTo(422);
        Response requested = approver.post("/api/v1/tenant/deletion-requests",
                Map.of("reason", "Contract ended; please delete our organisation."));
        assertThat(requested.status()).as(requested.body().toString()).isEqualTo(201);
        String id = requested.body().path("id").asString();
        assertThat(approver.post("/api/v1/tenant/deletion-requests", Map.of("reason", "A second open request."))
                .errorCode()).isEqualTo("LIFECYCLE_REQUEST_OPEN");

        // A queued job of the tenant, to see it cancelled after execution.
        UUID job = admin.queryForObject("""
                INSERT INTO app.job (tenant_id, job_type, idempotency_key, aggregate_type, aggregate_id, payload, priority,
                                     max_attempts, max_infra_failures, backoff_base_seconds, backoff_cap_seconds,
                                     lease_seconds, max_runtime_seconds)
                SELECT ?, 'DOCUMENT_SCAN', 'lifecycle-test-' || gen_random_uuid(), 'DOCUMENT', gen_random_uuid(), '{}', 50,
                       max_attempts, max_infra_failures, backoff_base_seconds, backoff_cap_seconds, lease_seconds,
                       max_runtime_seconds FROM app.job_type WHERE code = 'DOCUMENT_SCAN'
                RETURNING id
                """, UUID.class, org.id());

        // Staff without TENANT_DELETE are refused; approval before verification is refused.
        assertThat(c.post("/api/v1/staff/tenant-lifecycle-requests/" + id + "/verify", Map.of()).status())
                .isEqualTo(403);
        assertThat(a.post("/api/v1/staff/tenant-lifecycle-requests/" + id + "/approve", Map.of()).errorCode())
                .isEqualTo("LIFECYCLE_REQUEST_STATE");
        assertThat(a.post("/api/v1/staff/tenant-lifecycle-requests/" + id + "/verify", Map.of()).status())
                .isEqualTo(200);
        // The verifier cannot also approve (single-person deletion refused).
        assertThat(a.post("/api/v1/staff/tenant-lifecycle-requests/" + id + "/approve", Map.of()).errorCode())
                .isEqualTo("SECOND_APPROVER_REQUIRED");
        assertThat(admin.queryForObject("SELECT status FROM app.tenant WHERE id = ?", String.class, org.id()))
                .isEqualTo("ACTIVE");
        Response executed = b.post("/api/v1/staff/tenant-lifecycle-requests/" + id + "/approve", Map.of());
        assertThat(executed.status()).as(executed.body().toString()).isEqualTo(200);
        assertThat(executed.body().path("status").asString()).isEqualTo("EXECUTED");

        // Soft deletion: status DELETED, the tenant row and its users are still there.
        assertThat(admin.queryForObject("SELECT status FROM app.tenant WHERE id = ?", String.class, org.id()))
                .isEqualTo("DELETED");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.app_user WHERE tenant_id = ?", Integer.class,
                org.id())).isEqualTo(2);
        // Immediate revocation: existing sessions end, new logins fail, staff lose the active tenant.
        assertThat(user.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(approver.get("/api/v1/tenant/deletion-requests").status()).isEqualTo(401);
        assertThat(client().login(org.slug(), org.user(), TestDatabase.SEED_PASSWORD).status()).isEqualTo(401);
        assertThat(a.get("/api/v1/staff/manual-intake").status()).isEqualTo(403);
        // Background processing stops: the job is cancelled with a raised epoch; nothing is deleted.
        WorkerContext.get();
        assertThat(WorkerContext.bean(DeletedTenantJobCanceller.class).cancelJobsOfDeletedTenants()).isPositive();
        assertThat(admin.queryForMap("SELECT status, lease_epoch FROM app.job WHERE id = ?", job))
                .containsEntry("status", "CANCELLED");
        // Audit and security events.
        assertThat(admin.queryForList("""
                SELECT action FROM app.platform_audit_event WHERE target_ref = ? ORDER BY occurred_at
                """, String.class, id)).containsExactly("TENANT_DELETION_REQUESTED", "TENANT_LIFECYCLE_VERIFIED",
                "TENANT_SOFT_DELETED");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.platform_security_event WHERE type = 'TENANT_SOFT_DELETED' AND target_ref = ?
                """, Integer.class, org.id().toString())).isEqualTo(1);

        // Restoration: requested by one TENANT_DELETE holder, verified and approved by two others.
        Response restore = a.post("/api/v1/staff/tenants/" + org.id() + "/restoration-requests",
                Map.of("reason", "Deleted in error, confirmed by the client in writing."));
        assertThat(restore.status()).as(restore.body().toString()).isEqualTo(201);
        String restoreId = restore.body().path("id").asString();
        assertThat(a.post("/api/v1/staff/tenant-lifecycle-requests/" + restoreId + "/verify", Map.of()).errorCode())
                .isEqualTo("SELF_VERIFICATION_FORBIDDEN");
        assertThat(b.post("/api/v1/staff/tenant-lifecycle-requests/" + restoreId + "/verify", Map.of()).status())
                .isEqualTo(200);
        assertThat(b.post("/api/v1/staff/tenant-lifecycle-requests/" + restoreId + "/approve", Map.of()).errorCode())
                .isEqualTo("SECOND_APPROVER_REQUIRED");
        ApiClient t = client();
        t.post("/api/v1/staff/auth/login", Map.of("email", third.email(), "password", AnalysisScenario.PASSWORD));
        assertThat(t.post("/api/v1/staff/auth/mfa/verify", Map.of("code", StaffFixtures.apiCode(third.secret(), 0)))
                .status()).isEqualTo(200);
        assertThat(t.post("/api/v1/staff/tenant-lifecycle-requests/" + restoreId + "/approve", Map.of()).status())
                .isEqualTo(200);
        assertThat(admin.queryForObject("SELECT status FROM app.tenant WHERE id = ?", String.class, org.id()))
                .isEqualTo("ACTIVE");
        assertThat(loggedIn(org.slug(), org.user()).get("/api/v1/me").status()).isEqualTo(200);
    }

    @Test
    void theDatabaseRefusesSinglePersonDeletionAndDirectStatusChanges() throws Exception {
        Org org = org();
        try (Connection c = db.appConnection(); Statement st = c.createStatement()) {
            // The API login cannot change a tenant's status outside an executed request in the same transaction.
            assertThat(catchThrowable(() -> st.executeUpdate(
                    "UPDATE app.tenant SET status = 'DELETED' WHERE id = '" + org.id() + "'")))
                    .hasMessageContaining("approved lifecycle request");
            UUID user = admin.queryForObject("SELECT id FROM app.app_user WHERE tenant_id = ? AND role = 'ORG_ADMIN'",
                    UUID.class, org.id());
            UUID staffId = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("solo"), AnalysisScenario.PASSWORD)
                    .id();
            st.executeUpdate("""
                    INSERT INTO app.tenant_lifecycle_request (id, subject_tenant_id, kind, reason, requested_by_user)
                    VALUES ('%s', '%s', 'DELETE', 'Requested for the database test.', '%s')
                    """.formatted(UUID.randomUUID(), org.id(), user));
            // Verification by staff without TENANT_DELETE, and execution by the verifier, are refused.
            assertThat(catchThrowable(() -> st.executeUpdate("""
                    UPDATE app.tenant_lifecycle_request SET status = 'VERIFIED', verified_by = '%s',
                           verified_at = now() WHERE subject_tenant_id = '%s'
                    """.formatted(staffId, org.id())))).hasMessageContaining("authorised staff member");
        }
        // The worker and retention logins cannot change the tenant status at all.
        for (Connection c : new Connection[] {db.workerConnection(), db.retentionConnection()}) {
            try (c; Statement st = c.createStatement()) {
                assertThat(catchThrowable(() -> st.executeUpdate("UPDATE app.tenant SET status = 'DELETED'")))
                        .hasMessageContaining("permission denied");
                assertThat(catchThrowable(() -> st.executeUpdate("DELETE FROM app.tenant")))
                        .hasMessageContaining("permission denied");
            }
        }
    }
}
