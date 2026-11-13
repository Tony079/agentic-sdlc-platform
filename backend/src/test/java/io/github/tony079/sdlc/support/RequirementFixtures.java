package io.github.tony079.sdlc.support;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.support.ApiClient.Response;

/** Request bodies and helpers for requirement API tests. */
public final class RequirementFixtures {

    private RequirementFixtures() {
    }

    /**
     * A project of the logged-in user's organisation, with the same memberships the 0030 migration gives existing
     * data: the user is a REQUESTER (or an APPROVER if they are an Organisation Admin), and every Organisation Admin
     * of the organisation is an APPROVER. Created directly as the test superuser: through the API only an
     * Organisation Admin may create projects (FR-ORG-04), which ProjectApiIT and OrganisationAdminIT cover.
     */
    public static String project(ApiClient client) {
        Response me = client.get("/api/v1/me");
        if (me.status() != 200) {
            throw new IllegalStateException("Not signed in: " + me.status());
        }
        UUID tenant = UUID.fromString(me.body().path("tenant").path("id").asString());
        UUID user = UUID.fromString(me.body().path("userId").asString());
        return projectFor(tenant, user, "Req project " + UUID.randomUUID()).toString();
    }

    /** A project in the tenant, with {@code member} as REQUESTER (APPROVER if an admin) and all admins as APPROVER. */
    public static UUID projectFor(UUID tenant, UUID member, String name) {
        var admin = TestDatabase.get().admin();
        UUID orgAdmin = admin.queryForObject("""
                SELECT id FROM app.app_user WHERE tenant_id = ? AND role = 'ORG_ADMIN' AND status = 'ACTIVE'
                ORDER BY created_at LIMIT 1
                """, UUID.class, tenant);
        UUID project = admin.queryForObject(
                "INSERT INTO app.project (tenant_id, name, created_by) VALUES (?, ?, ?) RETURNING id", UUID.class,
                tenant, name, orgAdmin);
        admin.update("""
                INSERT INTO app.project_member (tenant_id, project_id, user_id, role, added_by)
                SELECT ?, ?, u.id, 'APPROVER', ? FROM app.app_user u
                WHERE u.tenant_id = ? AND u.role = 'ORG_ADMIN' AND u.status = 'ACTIVE'
                """, tenant, project, orgAdmin, tenant);
        admin.update("""
                INSERT INTO app.project_member (tenant_id, project_id, user_id, role, added_by)
                SELECT ?, ?, ?, 'REQUESTER', ?
                WHERE NOT EXISTS (SELECT 1 FROM app.project_member m WHERE m.project_id = ? AND m.user_id = ?
                                  AND m.removed_at IS NULL)
                """, tenant, project, member, orgAdmin, project, member);
        return project;
    }

    /** A new ACTIVE MEMBER of the organisation with the seed password, member of no project; returns the email. */
    public static String newMember(String tenantSlug, String prefix) {
        var admin = TestDatabase.get().admin();
        String hash = admin.queryForObject("SELECT password_hash FROM app.app_user WHERE email = 'alice@acme.test'",
                String.class);
        String email = prefix + "-" + UUID.randomUUID() + "@" + tenantSlug + ".test";
        admin.update("""
                INSERT INTO app.app_user (tenant_id, email, display_name, password_hash, role, status)
                VALUES (?, ?, 'Test member', ?, 'MEMBER', 'ACTIVE')
                """, TestDatabase.get().tenantId(tenantSlug), email, hash);
        return email;
    }

    /** Adds a user of the project's organisation to the project with a role (test setup as superuser). */
    public static void addMember(UUID project, UUID user, String role) {
        TestDatabase.get().admin().update("""
                INSERT INTO app.project_member (tenant_id, project_id, user_id, role, added_by)
                SELECT p.tenant_id, p.id, ?, ?, p.created_by FROM app.project p WHERE p.id = ?
                """, user, role, project);
    }

    /** All eight PRD fields, valid for submission, for an existing repository. */
    public static Map<String, Object> complete(String projectId) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("kind", "EXISTING_REPOSITORY");
        body.put("title", "Add repayment metadata " + UUID.randomUUID());
        body.put("description", "Store additional repayment metadata for each loan repayment.");
        body.put("businessObjective", "Improve reconciliation accuracy.");
        body.put("expectedOutcomes", "Repayments carry channel and reference fields.");
        body.put("acceptanceCriteria", "Metadata visible on the repayment API.");
        body.put("repositoryUrl", "https://github.com/acme-bank/loan-service");
        body.put("constraints", "None");
        body.put("integrations", "Not applicable");
        return body;
    }

    /** The minimum a draft needs: project, kind and title (decision Q2). */
    public static Map<String, Object> minimalDraft(String projectId, String kind) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("kind", kind);
        body.put("title", "Draft " + UUID.randomUUID());
        return body;
    }

    /** The content fields of an existing requirement's response, suitable for a PUT body. */
    public static Map<String, Object> contentOf(Response detail) {
        Map<String, Object> body = new HashMap<>();
        detail.body().path("content").properties().forEach(e ->
                body.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
        return body;
    }

    public static Map<String, String> ifMatch(Response detail) {
        return Map.of("If-Match", "\"" + detail.body().path("version").asLong() + "\"");
    }
}
