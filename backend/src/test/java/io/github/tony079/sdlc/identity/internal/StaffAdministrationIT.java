package io.github.tony079.sdlc.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GD-15: staff are invited and managed in the console. Every change is requested by one staff administrator and
 * approved by a different one, never the person changed; invited staff set their own password through a single-use
 * link and enrol an authenticator; the database refuses the same changes outside an approved request.
 */
class StaffAdministrationIT extends ApiIntegrationTest {

    private static final String PASSWORD = "staff-console-password-" + UUID.randomUUID();
    private final JdbcTemplate admin = TestDatabase.get().admin();

    private Enrolled staffAdmin(String prefix) {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail(prefix), AnalysisScenario.PASSWORD);
        StaffFixtures.grant(member.email(), StaffCapability.STAFF_ADMIN);
        return member;
    }

    private ApiClient signIn(String email, String password, byte[] secret) {
        ApiClient c = client();
        assertThat(c.post("/api/v1/staff/auth/login", Map.of("email", email, "password", password)).status())
                .isEqualTo(200);
        assertThat(c.post("/api/v1/staff/auth/mfa/verify", Map.of("code", StaffFixtures.apiCode(secret, 0))).status())
                .isEqualTo(200);
        return c;
    }

    private static String token(Response approved) {
        String link = approved.body().path("link").asString();
        assertThat(link).contains("/staff/");
        return link.substring(link.indexOf('#') + 1);
    }

    private Response request(ApiClient c, Map<String, Object> body) {
        return c.post("/api/v1/staff/admin/requests", body);
    }

    @Test
    void staffAreInvitedAndManagedThroughTwoPersonRequests() {
        Enrolled a = staffAdmin("console-a");
        Enrolled b = staffAdmin("console-b");
        ApiClient adminA = signIn(a.email(), AnalysisScenario.PASSWORD, a.secret());
        ApiClient adminB = signIn(b.email(), AnalysisScenario.PASSWORD, b.secret());
        String email = StaffFixtures.uniqueEmail("invited");

        // Invite with a capability: requested by A; A cannot approve; B approves and receives the link.
        Response invite = request(adminA, Map.of("kind", "INVITE", "email", email, "displayName", "Ivy Invited",
                "capabilities", List.of("BUSINESS_REVIEWER"), "reason", "New business reviewer for Q4."));
        assertThat(invite.status()).as(invite.body().toString()).isEqualTo(201);
        String inviteId = invite.body().path("id").asString();
        assertThat(adminA.post("/api/v1/staff/admin/requests/" + inviteId + "/approve", Map.of()).errorCode())
                .isEqualTo("SECOND_APPROVER_REQUIRED");
        Response approved = adminB.post("/api/v1/staff/admin/requests/" + inviteId + "/approve", Map.of());
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        String staffId = approved.body().path("request").path("subjectStaffId").asString();
        assertThat(admin.queryForObject("SELECT status FROM app.staff_user WHERE id = ?::uuid", String.class, staffId))
                .isEqualTo("INVITED");
        assertThat(client().post("/api/v1/staff/auth/login", Map.of("email", email, "password", PASSWORD)).status())
                .isEqualTo(401);

        // The invited person sets a password once, then enrols an authenticator at first sign-in.
        String token = token(approved);
        ApiClient anonymous = client();
        assertThat(anonymous.post("/api/v1/public/staff-invitations/lookup", Map.of("token", token)).body()
                .path("email").asString()).isEqualTo(email);
        assertThat(anonymous.post("/api/v1/public/staff-invitations/accept", Map.of("token", token, "password", PASSWORD))
                .status()).isEqualTo(204);
        assertThat(anonymous.post("/api/v1/public/staff-invitations/accept", Map.of("token", token, "password", PASSWORD))
                .errorCode()).isEqualTo("LINK_UNUSABLE");
        ApiClient invited = client();
        assertThat(invited.post("/api/v1/staff/auth/login", Map.of("email", email, "password", PASSWORD)).body()
                .path("status").asString()).isEqualTo("MFA_ENROLMENT_REQUIRED");
        byte[] secret = StaffFixtures.fromBase32(invited.post("/api/v1/staff/auth/mfa/enrolment", Map.of()).body()
                .path("secret").asString());
        assertThat(invited.post("/api/v1/staff/auth/mfa/enrolment/confirm",
                Map.of("code", StaffFixtures.apiCode(secret, 0))).status()).isEqualTo(200);
        assertThat(invited.get("/api/v1/staff/me").body().path("capabilities").toString()).contains("BUSINESS_REVIEWER");
        // Someone without STAFF_ADMIN cannot use staff administration.
        assertThat(invited.get("/api/v1/staff/admin/staff").status()).isEqualTo(403);

        // Grant a capability (two people), then disable: the session ends.
        String grantId = request(adminA, Map.of("kind", "GRANT_CAPABILITY", "subjectStaffId", staffId,
                "capability", "ORG_ONBOARD", "reason", "Joins the onboarding team.")).body().path("id").asString();
        assertThat(adminB.post("/api/v1/staff/admin/requests/" + grantId + "/approve", Map.of()).status()).isEqualTo(200);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.staff_capability_grant WHERE staff_id = ?::uuid AND revoked_at IS NULL
                """, Integer.class, staffId)).isEqualTo(2);
        assertThat(request(adminA, Map.of("kind", "GRANT_CAPABILITY", "subjectStaffId", staffId,
                "capability", "ORG_ONBOARD", "reason", "Duplicate request.")).errorCode()).isEqualTo("STAFF_STATE");
        String disableId = request(adminB, Map.of("kind", "DISABLE", "subjectStaffId", staffId,
                "reason", "Left the company.")).body().path("id").asString();
        assertThat(adminA.post("/api/v1/staff/admin/requests/" + disableId + "/approve", Map.of()).status())
                .isEqualTo(200);
        assertThat(invited.get("/api/v1/staff/me").status()).isNotEqualTo(200);

        // Nobody changes their own account, and nobody approves a change to themselves.
        assertThat(request(adminA, Map.of("kind", "DISABLE", "subjectStaffId", a.id().toString(),
                "reason", "Testing self change.")).errorCode()).isEqualTo("SELF_CHANGE_FORBIDDEN");
        String resetB = request(adminA, Map.of("kind", "RESET_MFA", "subjectStaffId", b.id().toString(),
                "reason", "Lost phone.")).body().path("id").asString();
        assertThat(adminB.post("/api/v1/staff/admin/requests/" + resetB + "/approve", Map.of()).errorCode())
                .isEqualTo("SELF_CHANGE_FORBIDDEN");
        assertThat(adminA.post("/api/v1/staff/admin/requests/" + resetB + "/cancel", Map.of()).status()).isEqualTo(200);

        assertThat(admin.queryForList("SELECT action FROM app.platform_audit_event WHERE target_ref = ?", String.class,
                staffId)).contains("STAFF_INVITE_EXECUTED", "STAFF_INVITATION_ACCEPTED", "STAFF_GRANT_CAPABILITY_EXECUTED",
                "STAFF_DISABLE_EXECUTED");
    }

    @Test
    void theDatabaseRefusesStaffChangesOutsideAnApprovedRequest() throws Exception {
        Enrolled a = staffAdmin("db-a");
        Enrolled target = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("db-target"), AnalysisScenario.PASSWORD);
        try (Connection c = TestDatabase.get().appConnection(); Statement st = c.createStatement()) {
            st.execute("SET app.actor_id = '" + a.id() + "'");
            assertThat(catchThrowable(() -> st.executeUpdate("""
                    INSERT INTO app.staff_user (id, email, display_name, status)
                    VALUES (gen_random_uuid(), 'rogue@staff.test', 'Rogue', 'INVITED')
                    """))).hasMessageContaining("approved invitation");
            assertThat(catchThrowable(() -> st.executeUpdate("""
                    INSERT INTO app.staff_capability_grant (staff_id, capability, requested_by, approved_by, operator)
                    VALUES ('%s', 'STAFF_ADMIN', '%s', '%s', 'rogue')
                    """.formatted(target.id(), a.id(), StaffFixtures.admins()[0].id()))))
                    .hasMessageContaining("approved staff request");
            assertThat(catchThrowable(() -> st.executeUpdate(
                    "UPDATE app.staff_user SET status = 'DISABLED' WHERE id = '" + target.id() + "'")))
                    .hasMessageContaining("approved staff request");
            // A request may be approved only by a second administrator acting as themselves.
            st.executeUpdate("""
                    INSERT INTO app.staff_admin_request (id, kind, subject_staff_id, reason, requested_by)
                    VALUES ('%s', 'DISABLE', '%s', 'Database test.', '%s')
                    """.formatted(UUID.randomUUID(), target.id(), a.id()));
            assertThat(catchThrowable(() -> st.executeUpdate("""
                    UPDATE app.staff_admin_request SET status = 'EXECUTED', approved_by = '%s', executed_at = now()
                    WHERE subject_staff_id = '%s'
                    """.formatted(a.id(), target.id())))).hasMessageContaining("second active staff administrator");
        }
    }
}
