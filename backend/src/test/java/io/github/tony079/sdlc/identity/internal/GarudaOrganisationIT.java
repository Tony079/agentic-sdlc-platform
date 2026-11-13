package io.github.tony079.sdlc.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Garuda organisation model end to end (FR-ORG-01..08, FR-DSH-01, GD-03/04/05/07): two-person onboarding, the first
 * Organisation Admin's emailed invitation, project-scoped member invitations, role changes, deactivation, password
 * resets, suspension and the cross-organisation dashboard; and the database refusing the same actions outside the rules.
 */
class GarudaOrganisationIT extends ApiIntegrationTest {

    private static final ServerSocket SMTP;
    private static final List<String> MAILS = new CopyOnWriteArrayList<>();
    private static final Pattern TOKEN = Pattern.compile("/o/[a-z0-9-]+/(invite|reset)#([A-Za-z0-9_-]{43})");
    private static final String PASSWORD = "a-long-client-password-" + UUID.randomUUID();

    static {
        try {
            SMTP = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Thread.ofVirtual().start(GarudaOrganisationIT::serveSmtp);
    }

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("sdlc.mail.host", () -> "127.0.0.1");
        registry.add("sdlc.mail.port", SMTP::getLocalPort);
        registry.add("sdlc.mail.from", () -> "garuda@platform.test");
        registry.add("sdlc.mail.allow-loopback-plaintext", () -> "true");
        registry.add("sdlc.public-base-url", () -> "https://garuda.example.test");
    }

    @AfterAll
    static void stopSmtp() throws IOException {
        SMTP.close();
    }

    private final JdbcTemplate admin = TestDatabase.get().admin();

    private ApiClient staff(Enrolled member) {
        ApiClient c = client();
        assertThat(c.post("/api/v1/staff/auth/login", Map.of("email", member.email(), "password",
                AnalysisScenario.PASSWORD)).status()).isEqualTo(200);
        assertThat(c.post("/api/v1/staff/auth/mfa/verify", Map.of("code", StaffFixtures.apiCode(member.secret(), 0)))
                .status()).isEqualTo(200);
        return c;
    }

    private Enrolled onboarder() {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("onboard"), AnalysisScenario.PASSWORD);
        StaffFixtures.grant(member.email(), StaffCapability.ORG_ONBOARD);
        return member;
    }

    private static String token(String link) {
        Matcher m = TOKEN.matcher(link);
        assertThat(m.find()).as(link).isTrue();
        return m.group(2);
    }

    /** The decoded text of the last email to the recipient. */
    private static String mailTo(String recipient) {
        for (int i = MAILS.size() - 1; i >= 0; i--) {
            String raw = MAILS.get(i);
            if (raw.contains("To: <" + recipient + ">")) {
                StringBuilder decoded = new StringBuilder(raw);
                for (String part : raw.split("\n\n")) {
                    String b64 = part.split("\n--")[0].replace("\n", "").strip();
                    if (b64.matches("^[A-Za-z0-9+/=]{20,}$")) {
                        decoded.append('\n').append(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
                    }
                }
                return decoded.toString();
            }
        }
        return null;
    }

    private static long mailCount(String recipient) {
        return MAILS.stream().filter(m -> m.contains("To: <" + recipient + ">")).count();
    }

    @Test
    void onboardInviteAdministerAndSuspendAnOrganisation() {
        Enrolled a = onboarder();
        Enrolled b = onboarder();
        Enrolled outsider = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("no-cap"), AnalysisScenario.PASSWORD);
        ApiClient staffA = staff(a);
        ApiClient staffB = staff(b);
        ApiClient staffC = staff(outsider);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = "garuda-" + suffix;
        String adminEmail = "first.admin@" + slug + ".test";

        // FR-ORG-01: one person requests, a different person approves; staff without ORG_ONBOARD are refused.
        assertThat(staffC.get("/api/v1/staff/organisation-requests").status()).isEqualTo(403);
        assertThat(staffA.post("/api/v1/staff/organisation-requests", Map.of("organisationName", "Bad", "slug", "-x",
                "adminEmail", adminEmail, "adminDisplayName", "First Admin")).status()).isEqualTo(422);
        Response requested = staffA.post("/api/v1/staff/organisation-requests", Map.of("organisationName",
                "Garuda Client " + suffix, "slug", slug, "adminEmail", adminEmail, "adminDisplayName", "First Admin"));
        assertThat(requested.status()).as(requested.body().toString()).isEqualTo(201);
        String requestId = requested.body().path("id").asString();
        assertThat(staffA.post("/api/v1/staff/organisation-requests/" + requestId + "/approve", Map.of()).errorCode())
                .isEqualTo("SECOND_APPROVER_REQUIRED");
        assertThat(staffC.post("/api/v1/staff/organisation-requests/" + requestId + "/approve", Map.of()).status())
                .isEqualTo(403);
        Response approved = staffB.post("/api/v1/staff/organisation-requests/" + requestId + "/approve", Map.of());
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        assertThat(approved.body().path("request").path("status").asString()).isEqualTo("EXECUTED");
        assertThat(approved.body().path("emailStatus").asString()).isEqualTo("SENT");
        String link = approved.body().path("invitationLink").asString();
        assertThat(link).startsWith("https://garuda.example.test/o/" + slug + "/invite#");
        UUID tenantId = UUID.fromString(approved.body().path("request").path("createdTenantId").asString());
        // GD-04: the same link was emailed; only its hash is stored.
        assertThat(mailTo(adminEmail)).contains(link).contains("Garuda Client " + suffix);
        String adminToken = token(link);
        assertThat(admin.queryForObject("SELECT count(*) FROM app.user_invitation WHERE token_sha256 = ?",
                Integer.class, AccountTokens.hash(adminToken))).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM app.user_invitation WHERE token_sha256 = ?",
                Integer.class, adminToken)).isZero();

        // FR-ORG-07: invitation lookup and acceptance; a weak password is refused; the link works once.
        ApiClient anonymous = client();
        Response lookup = anonymous.post("/api/v1/public/invitations/lookup", Map.of("organisation", slug,
                "token", adminToken));
        assertThat(lookup.status()).isEqualTo(200);
        assertThat(lookup.body().path("email").asString()).isEqualTo(adminEmail);
        assertThat(anonymous.post("/api/v1/public/invitations/accept", Map.of("organisation", slug, "token", adminToken,
                "password", "short")).status()).isEqualTo(422);
        assertThat(anonymous.post("/api/v1/public/invitations/accept", Map.of("organisation", slug, "token", adminToken,
                "password", PASSWORD)).status()).isEqualTo(204);
        assertThat(anonymous.post("/api/v1/public/invitations/accept", Map.of("organisation", slug, "token", adminToken,
                "password", PASSWORD)).errorCode()).isEqualTo("LINK_UNUSABLE");
        assertThat(anonymous.post("/api/v1/public/invitations/lookup", Map.of("organisation", slug,
                "token", adminToken)).status()).isEqualTo(410);

        ApiClient orgAdmin = client();
        assertThat(orgAdmin.login(slug, adminEmail, PASSWORD).status()).isEqualTo(200);
        assertThat(orgAdmin.get("/api/v1/me").body().path("roles").get(0).asString()).isEqualTo("ORG_ADMIN");
        Response summary = orgAdmin.get("/api/v1/organisation");
        assertThat(summary.body().path("activeUsers").asInt()).isEqualTo(1);
        assertThat(summary.body().path("emailConfigured").asBoolean()).isTrue();

        // FR-ORG-04/05: projects, and a member invited straight into one of them with a project role.
        String alpha = orgAdmin.post("/api/v1/projects", Map.of("name", "Alpha")).body().path("id").asString();
        String beta = orgAdmin.post("/api/v1/projects", Map.of("name", "Beta")).body().path("id").asString();
        String memberEmail = "member@" + slug + ".test";
        Response invited = orgAdmin.post("/api/v1/organisation/users", Map.of("email", memberEmail,
                "displayName", "Mia Member", "role", "MEMBER",
                "memberships", List.of(Map.of("projectId", alpha, "role", "REQUESTER"))));
        assertThat(invited.status()).as(invited.body().toString()).isEqualTo(201);
        assertThat(invited.body().path("emailStatus").asString()).isEqualTo("SENT");
        String memberId = invited.body().path("userId").asString();
        assertThat(orgAdmin.post("/api/v1/organisation/users", Map.of("email", memberEmail.toUpperCase(),
                "displayName", "Again", "role", "MEMBER")).errorCode()).isEqualTo("USER_EXISTS");
        // An invited user cannot sign in yet.
        assertThat(client().login(slug, memberEmail, PASSWORD).status()).isEqualTo(401);
        // Resend revokes the first link.
        String firstMemberToken = token(invited.body().path("link").asString());
        String memberToken = token(orgAdmin.post("/api/v1/organisation/users/" + memberId + "/invitation/resend",
                Map.of()).body().path("link").asString());
        assertThat(anonymous.post("/api/v1/public/invitations/lookup", Map.of("organisation", slug,
                "token", firstMemberToken)).status()).isEqualTo(410);
        assertThat(mailTo(memberEmail)).contains(memberToken);
        assertThat(anonymous.post("/api/v1/public/invitations/accept", Map.of("organisation", slug, "token", memberToken,
                "password", PASSWORD)).status()).isEqualTo(204);

        ApiClient member = client();
        assertThat(member.login(slug, memberEmail, PASSWORD).status()).isEqualTo(200);
        Response memberProjects = member.get("/api/v1/projects");
        assertThat(memberProjects.body().path("items").size()).isEqualTo(1);
        assertThat(memberProjects.body().path("items").get(0).path("myRole").asString()).isEqualTo("REQUESTER");
        assertThat(member.get("/api/v1/projects/" + beta).status()).isEqualTo(404);
        assertThat(member.get("/api/v1/organisation/users").status()).isEqualTo(403);
        assertThat(member.post("/api/v1/organisation/users", Map.of("email", "x@" + slug + ".test",
                "displayName", "X", "role", "ORG_ADMIN")).status()).isEqualTo(403);

        Response users = orgAdmin.get("/api/v1/organisation/users");
        assertThat(users.body().size()).isEqualTo(2);
        assertThat(users.body().findValues("projectName").toString()).contains("Alpha");

        // Self-changes are refused; a role change ends the member's session at the next request.
        String adminId = orgAdmin.get("/api/v1/me").body().path("userId").asString();
        assertThat(orgAdmin.post("/api/v1/organisation/users/" + adminId + "/deactivate", Map.of()).errorCode())
                .isEqualTo("SELF_CHANGE_FORBIDDEN");
        assertThat(orgAdmin.put("/api/v1/organisation/users/" + memberId + "/role", Map.of("role", "ORG_ADMIN"))
                .status()).isEqualTo(204);
        assertThat(member.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(orgAdmin.put("/api/v1/organisation/users/" + memberId + "/role", Map.of("role", "MEMBER"))
                .status()).isEqualTo(204);

        // Deactivation blocks sign-in until reactivation.
        member = client();
        assertThat(member.login(slug, memberEmail, PASSWORD).status()).isEqualTo(200);
        assertThat(orgAdmin.post("/api/v1/organisation/users/" + memberId + "/deactivate", Map.of()).status())
                .isEqualTo(204);
        assertThat(member.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(client().login(slug, memberEmail, PASSWORD).status()).isEqualTo(401);
        assertThat(orgAdmin.post("/api/v1/organisation/users/" + memberId + "/reactivate", Map.of()).status())
                .isEqualTo(204);
        assertThat(client().login(slug, memberEmail, PASSWORD).status()).isEqualTo(200);

        // FR-ORG-08: an admin-issued reset link sets a new password once.
        String newPassword = "another-long-password-" + UUID.randomUUID();
        String resetToken = token(orgAdmin.post("/api/v1/organisation/users/" + memberId + "/password-reset", Map.of())
                .body().path("link").asString());
        assertThat(anonymous.post("/api/v1/public/invitations/accept", Map.of("organisation", slug, "token", resetToken,
                "password", newPassword)).status()).isEqualTo(410);
        assertThat(anonymous.post("/api/v1/public/password-resets/complete", Map.of("organisation", slug,
                "token", resetToken, "password", newPassword)).status()).isEqualTo(204);
        assertThat(client().login(slug, memberEmail, PASSWORD).status()).isEqualTo(401);
        assertThat(client().login(slug, memberEmail, newPassword).status()).isEqualTo(200);

        // Self-service "forgot password": same answer for unknown accounts; the link is only emailed; rate-limited.
        long before = mailCount(memberEmail);
        assertThat(anonymous.post("/api/v1/public/password-resets", Map.of("organisation", slug,
                "email", "nobody@" + slug + ".test")).status()).isEqualTo(202);
        assertThat(anonymous.post("/api/v1/public/password-resets", Map.of("organisation", slug,
                "email", memberEmail)).status()).isEqualTo(202);
        assertThat(mailCount(memberEmail)).isEqualTo(before + 1);
        assertThat(anonymous.post("/api/v1/public/password-resets", Map.of("organisation", slug,
                "email", memberEmail)).status()).isEqualTo(202);
        assertThat(mailCount(memberEmail)).isEqualTo(before + 1);
        String selfToken = token(mailTo(memberEmail));
        assertThat(anonymous.post("/api/v1/public/password-resets/lookup", Map.of("organisation", slug,
                "token", selfToken)).status()).isEqualTo(200);

        // FR-DSH-01, GD-07: the dashboard lists the organisation, its admins and requirement counts; not for others.
        assertThat(staffC.get("/api/v1/staff/platform/dashboard").status()).isEqualTo(403);
        Response dashboard = staffA.get("/api/v1/staff/platform/dashboard");
        assertThat(dashboard.status()).isEqualTo(200);
        String orgs = dashboard.body().path("organisationRows").toString();
        assertThat(orgs).contains(slug).contains(adminEmail).doesNotContain(memberEmail);
        Response detail = staffA.get("/api/v1/staff/platform/organisations/" + tenantId);
        assertThat(detail.body().path("projects").size()).isEqualTo(2);

        // FR-ORG-03: suspension by two people ends sessions and sign-in; reactivation restores them.
        Response suspend = staffA.post("/api/v1/staff/organisations/" + tenantId + "/status-requests",
                Map.of("kind", "SUSPEND", "reason", "Invoice overdue by ninety days."));
        assertThat(suspend.status()).as(suspend.body().toString()).isEqualTo(201);
        String suspendId = suspend.body().path("id").asString();
        assertThat(staffA.post("/api/v1/staff/organisation-status-requests/" + suspendId + "/approve", Map.of())
                .errorCode()).isEqualTo("SECOND_APPROVER_REQUIRED");
        assertThat(staffB.post("/api/v1/staff/organisation-status-requests/" + suspendId + "/approve", Map.of())
                .status()).isEqualTo(200);
        assertThat(admin.queryForObject("SELECT status FROM app.tenant WHERE id = ?", String.class, tenantId))
                .isEqualTo("SUSPENDED");
        assertThat(orgAdmin.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(client().login(slug, adminEmail, PASSWORD).status()).isEqualTo(401);
        String reactivateId = staffB.post("/api/v1/staff/organisations/" + tenantId + "/status-requests",
                Map.of("kind", "REACTIVATE", "reason", "Invoice paid in full today.")).body().path("id").asString();
        assertThat(staffA.post("/api/v1/staff/organisation-status-requests/" + reactivateId + "/approve", Map.of())
                .status()).isEqualTo(200);
        assertThat(client().login(slug, adminEmail, PASSWORD).status()).isEqualTo(200);

        assertThat(admin.queryForList("""
                SELECT action FROM app.platform_audit_event WHERE tenant_ref = ? ORDER BY occurred_at
                """, String.class, tenantId)).contains("ORGANISATION_CREATED", "ORGANISATION_SUSPENDED",
                "ORGANISATION_REACTIVATED");
        assertThat(admin.queryForList("SELECT action FROM app.audit_event WHERE tenant_id = ?", String.class, tenantId))
                .contains("USER_INVITED", "USER_INVITATION_ACCEPTED", "USER_ROLE_CHANGED", "USER_DEACTIVATED",
                        "USER_REACTIVATED", "PASSWORD_RESET_REQUESTED", "PASSWORD_RESET_COMPLETED");
    }

    @Test
    void theDatabaseRefusesOrganisationsUsersAndLinksOutsideTheRules() throws Exception {
        UUID tenant = admin.queryForObject("SELECT id FROM app.tenant WHERE slug = 'acme'", UUID.class);
        UUID member = admin.queryForObject("SELECT id FROM app.app_user WHERE tenant_id = ? AND role = 'MEMBER' LIMIT 1",
                UUID.class, tenant);
        try (Connection c = TestDatabase.get().appConnection(); Statement st = c.createStatement()) {
            assertThat(catchThrowable(() -> st.executeUpdate(
                    "INSERT INTO app.tenant (id, slug, name, status) VALUES (gen_random_uuid(), 'rogue-org', 'Rogue', 'ACTIVE')")))
                    .hasMessageContaining("approved onboarding request");
            st.execute("SET app.tenant_id = '" + tenant + "'");
            assertThat(catchThrowable(() -> st.executeUpdate("""
                    INSERT INTO app.app_user (tenant_id, email, display_name, role, status)
                    VALUES ('%s', 'rogue@acme.test', 'Rogue', 'ORG_ADMIN', 'INVITED')
                    """.formatted(tenant)))).hasMessageContaining("only by an Organisation Admin");
            assertThat(catchThrowable(() -> st.executeUpdate("""
                    INSERT INTO app.user_invitation (tenant_id, user_id, purpose, token_sha256, expires_at)
                    VALUES ('%s', '%s', 'INVITE', repeat('a', 64), clock_timestamp() + interval '1 hour')
                    """.formatted(tenant, member)))).hasMessageContaining("created by an Organisation Admin");
            // A member acting on themselves cannot become an admin, and nobody sets another user's password directly.
            st.execute("SET app.actor_id = '" + member + "'");
            assertThat(catchThrowable(() -> st.executeUpdate(
                    "UPDATE app.app_user SET role = 'ORG_ADMIN' WHERE id = '" + member + "'")))
                    .hasMessageContaining("another Organisation Admin");
            assertThat(catchThrowable(() -> st.executeUpdate(
                    "UPDATE app.app_user SET password_hash = 'x' WHERE id <> '" + member + "'")))
                    .hasMessageContaining("only by its user");
        }
    }

    private static void serveSmtp() {
        while (!SMTP.isClosed()) {
            try (Socket s = SMTP.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = s.getOutputStream();
                out.write("220 test\r\n".getBytes());
                StringBuilder data = null;
                String line;
                while ((line = in.readLine()) != null) {
                    if (data != null) {
                        if (line.equals(".")) {
                            MAILS.add(data.toString());
                            data = null;
                            out.write("250 queued\r\n".getBytes());
                        } else {
                            data.append(line).append('\n');
                        }
                        continue;
                    }
                    if (line.startsWith("EHLO")) {
                        out.write("250-test\r\n250 OK\r\n".getBytes());
                    } else if (line.equals("DATA")) {
                        data = new StringBuilder();
                        out.write("354 go\r\n".getBytes());
                    } else if (line.equals("QUIT")) {
                        out.write("221 bye\r\n".getBytes());
                        break;
                    } else {
                        out.write("250 ok\r\n".getBytes());
                    }
                }
            } catch (IOException e) {
                if (SMTP.isClosed()) {
                    return;
                }
            }
        }
    }
}
