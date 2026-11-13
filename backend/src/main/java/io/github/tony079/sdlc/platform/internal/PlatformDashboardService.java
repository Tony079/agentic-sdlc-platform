package io.github.tony079.sdlc.platform.internal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Garuda dashboard across client organisations (FR-DSH-01..03, GD-07): organisations with their counts, every
 * requirement's key, title, project, state and age, and bottlenecks. Never shows descriptions, documents, BRD
 * content, clarification text or the email addresses of users other than Organisation Admins. Each organisation is
 * read inside its own tenant scope (row-level security), one read-only transaction per organisation.
 */
@Service
@ConditionalOnWebApplication
class PlatformDashboardService {

    /** Days in one state after which a requirement is listed as a bottleneck. */
    static final int STALE_DAYS = 5;

    record Overview(int organisations, int activeOrganisations, int suspendedOrganisations, int users, int projects,
                    int repositories, int requirements, Map<String, Integer> requirementsByState,
                    List<OrganisationRow> organisationRows, List<RequirementRow> bottlenecks, int staleDays) {
    }

    record OrganisationRow(UUID id, String name, String slug, String status, OffsetDateTime createdAt, int activeUsers,
                           int invitedUsers, int projects, int repositories, int requirements, int openRequirements,
                           int bottlenecks, OffsetDateTime lastActivityAt, List<Admin> admins) {
    }

    record Admin(String displayName, String email, String status) {
    }

    record RequirementRow(UUID organisationId, String organisationName, UUID projectId, String projectName, String key,
                          String title, String state, OffsetDateTime createdAt, OffsetDateTime stateSince,
                          int daysInState, String bottleneck) {
    }

    record ProjectRow(UUID id, String name, String status, int members, int requirements) {
    }

    record OrganisationDetail(OrganisationRow organisation, List<ProjectRow> projects,
                              List<RequirementRow> requirements) {
    }

    private record Tenant(UUID id, String name, String slug, String status, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final StaffAccess staffAccess;

    PlatformDashboardService(JdbcClient jdbc, PlatformTransactionManager transactionManager, StaffAccess staffAccess) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.staffAccess = staffAccess;
    }

    Overview overview() {
        authorise();
        List<Tenant> tenants = readOnly.execute(s -> jdbc.sql("""
                SELECT id, name, slug, status, created_at FROM app.tenant WHERE status IN ('ACTIVE', 'SUSPENDED')
                ORDER BY lower(name)
                """).query((rs, n) -> new Tenant(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class))).list());
        List<OrganisationRow> rows = new ArrayList<>();
        List<RequirementRow> bottlenecks = new ArrayList<>();
        Map<String, Integer> byState = new LinkedHashMap<>();
        int users = 0;
        int projects = 0;
        int repositories = 0;
        int requirements = 0;
        for (Tenant tenant : tenants) {
            List<RequirementRow> reqs = requirements(tenant);
            OrganisationRow row = organisation(tenant, reqs);
            rows.add(row);
            users += row.activeUsers();
            projects += row.projects();
            repositories += row.repositories();
            requirements += row.requirements();
            for (RequirementRow r : reqs) {
                byState.merge(r.state(), 1, Integer::sum);
                if (r.bottleneck() != null) {
                    bottlenecks.add(r);
                }
            }
        }
        bottlenecks.sort((a, b) -> Integer.compare(b.daysInState(), a.daysInState()));
        int active = (int) rows.stream().filter(r -> "ACTIVE".equals(r.status())).count();
        return new Overview(rows.size(), active, rows.size() - active, users, projects, repositories, requirements,
                byState, rows, bottlenecks.stream().limit(100).toList(), STALE_DAYS);
    }

    OrganisationDetail organisation(UUID tenantId) {
        authorise();
        Tenant tenant = readOnly.execute(s -> jdbc.sql(
                "SELECT id, name, slug, status, created_at FROM app.tenant WHERE id = :id AND status IN ('ACTIVE', 'SUSPENDED')")
                .param("id", tenantId)
                .query((rs, n) -> new Tenant(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class)))
                .optional().orElseThrow(ApiException::notFound));
        List<RequirementRow> reqs = requirements(tenant);
        List<ProjectRow> projectRows = TenantContext.callAs(tenant.id(), () -> readOnly.execute(s -> jdbc.sql("""
                SELECT p.id, p.name, p.status,
                       (SELECT count(*) FROM app.project_member m WHERE m.project_id = p.id AND m.removed_at IS NULL),
                       (SELECT count(*) FROM app.requirement r WHERE r.project_id = p.id)
                FROM app.project p ORDER BY p.status, lower(p.name)
                """).query((rs, n) -> new ProjectRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5))).list()));
        return new OrganisationDetail(organisation(tenant, reqs), projectRows, reqs);
    }

    private void authorise() {
        readOnly.execute(s -> staffAccess.requireAnyPlatformCapability(StaffCapability.ORG_ONBOARD,
                StaffCapability.STAFF_ADMIN, StaffCapability.AUDITOR));
    }

    private OrganisationRow organisation(Tenant tenant, List<RequirementRow> reqs) {
        return TenantContext.callAs(tenant.id(), () -> readOnly.execute(s -> {
            Map<String, Object> counts = jdbc.sql("""
                    SELECT (SELECT count(*) FROM app.app_user WHERE status = 'ACTIVE') AS active_users,
                           (SELECT count(*) FROM app.app_user WHERE status = 'INVITED') AS invited_users,
                           (SELECT count(*) FROM app.project WHERE status = 'ACTIVE') AS projects,
                           (SELECT count(*) FROM app.repository) AS repositories,
                           (SELECT max(updated_at) FROM app.requirement) AS last_activity
                    """).query().singleRow();
            List<Admin> admins = jdbc.sql("""
                    SELECT display_name, email, status FROM app.app_user WHERE role = 'ORG_ADMIN' AND status <> 'DISABLED'
                    ORDER BY lower(display_name)
                    """).query((rs, n) -> new Admin(rs.getString(1), rs.getString(2), rs.getString(3))).list();
            int open = (int) reqs.stream().filter(r -> !TERMINAL.contains(r.state())).count();
            int stuck = (int) reqs.stream().filter(r -> r.bottleneck() != null).count();
            Object last = counts.get("last_activity");
            return new OrganisationRow(tenant.id(), tenant.name(), tenant.slug(), tenant.status(), tenant.createdAt(),
                    ((Number) counts.get("active_users")).intValue(), ((Number) counts.get("invited_users")).intValue(),
                    ((Number) counts.get("projects")).intValue(), ((Number) counts.get("repositories")).intValue(),
                    reqs.size(), open, stuck, last instanceof OffsetDateTime t ? t : toOffset(last), admins);
        }));
    }

    private static final List<String> TERMINAL = List.of("REJECTED", "CANCELLED", "SHARED_WITH_CLIENT");

    private List<RequirementRow> requirements(Tenant tenant) {
        return TenantContext.callAs(tenant.id(), () -> readOnly.execute(s -> jdbc.sql("""
                SELECT p.id, p.name, r.key, rev.title, wi.projected_state, r.created_at, wi.updated_at,
                       floor(extract(epoch FROM clock_timestamp() - wi.updated_at) / 86400)::int
                FROM app.requirement r
                JOIN app.requirement_revision rev ON rev.requirement_id = r.id AND rev.revision_no = r.current_revision_no
                JOIN app.workflow_instance wi ON wi.requirement_id = r.id
                JOIN app.project p ON p.id = r.project_id
                ORDER BY wi.updated_at DESC
                LIMIT 1000
                """).query((rs, n) -> {
                    String state = rs.getString(5);
                    int days = rs.getInt(8);
                    return new RequirementRow(tenant.id(), tenant.name(), rs.getObject(1, UUID.class), rs.getString(2),
                            rs.getString(3), rs.getString(4), state, rs.getObject(6, OffsetDateTime.class),
                            rs.getObject(7, OffsetDateTime.class), days, bottleneck(state, days));
                }).list()));
    }

    /** Why a requirement is stuck, or null. Failed analyses always count; waiting states after STALE_DAYS. */
    static String bottleneck(String state, int days) {
        if ("FAILED".equals(state)) {
            return "FAILED";
        }
        if (days < STALE_DAYS) {
            return null;
        }
        return switch (state) {
            case "AWAITING_CLARIFICATION" -> "WAITING_FOR_CLIENT";
            case "AWAITING_DOCUMENTS", "AWAITING_DOCUMENT_DECISION", "CLIENT_CHANGES_REQUESTED" -> "WAITING_FOR_CLIENT";
            case "BRD_DRAFTED", "INTERNAL_REVIEW", "REVISION_REQUESTED" -> "WAITING_FOR_REVIEW";
            case "INTERNAL_APPROVED" -> "WAITING_FOR_RELEASE";
            case "MANUAL_INTAKE", "SUBMITTED", "REPO_VALIDATING" -> "WAITING_FOR_INTAKE";
            default -> null;
        };
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        return null;
    }
}
