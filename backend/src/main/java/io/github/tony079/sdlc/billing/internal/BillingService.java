package io.github.tony079.sdlc.billing.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.common.api.CorrelationId;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Billing for Garuda (GD-19, GD-22): billing plans, the activation of pending projects with a plan, and the monthly
 * billing report. Every decision is proposed by one Garuda Admin and approved by another (database-enforced).
 */
@Service
@ConditionalOnWebApplication
class BillingService {

    record Plan(UUID id, String name, String currency, BigDecimal monthlyFee, int includedBrds, BigDecimal priceSmall,
                BigDecimal priceMedium, BigDecimal priceLarge, String status, UUID proposedBy, String proposedByName,
                OffsetDateTime proposedAt, UUID approvedBy, String approvedByName, OffsetDateTime approvedAt) {
    }

    record NewPlan(String name, String currency, BigDecimal monthlyFee, Integer includedBrds, BigDecimal priceSmall,
                   BigDecimal priceMedium, BigDecimal priceLarge) {
    }

    record Decision(UUID id, String decision, UUID planId, String planName, String note, String status, UUID requestedBy,
                    String requestedByName, OffsetDateTime requestedAt, UUID approvedBy, OffsetDateTime executedAt) {
    }

    record PendingProject(UUID tenantId, String organisationName, UUID projectId, String name, String description,
                          String status, OffsetDateTime createdAt, int members, Decision openDecision) {
    }

    record NewDecision(UUID tenantId, UUID projectId, String decision, UUID planId, String note) {
    }

    record ReportLine(UUID tenantId, String organisationName, UUID projectId, String projectName, String planName,
                      String currency, BigDecimal monthlyFee, LocalDate billedFrom, LocalDate billedTo, int activeDays,
                      int daysInMonth, BigDecimal projectFee) {
    }

    record Total(String currency, BigDecimal amount) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate auditTx;
    private final StaffAccess staffAccess;

    BillingService(JdbcClient jdbc, PlatformTransactionManager transactionManager, StaffAccess staffAccess) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.auditTx = new TransactionTemplate(transactionManager);
        this.auditTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.staffAccess = staffAccess;
    }

    // --- plans ------------------------------------------------------------------------------------------------

    List<Plan> plans() {
        return tx.execute(s -> {
            admin();
            return jdbc.sql(PLAN_SELECT + " ORDER BY p.status <> 'ACTIVE', p.status <> 'PROPOSED', lower(p.name)")
                    .query((rs, n) -> plan(rs)).list();
        });
    }

    Plan proposePlan(NewPlan body) {
        String name = body.name() == null ? "" : body.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw ApiException.validation("Enter a plan name.");
        }
        if (body.currency() == null || !List.of("INR", "USD", "EUR", "GBP").contains(body.currency())) {
            throw ApiException.validation("Choose a currency.");
        }
        for (BigDecimal v : new BigDecimal[] {body.monthlyFee(), body.priceSmall(), body.priceMedium(), body.priceLarge()}) {
            if (v == null || v.signum() < 0) {
                throw ApiException.validation("Enter the monthly fee and the price of each BRD size (0 or more).");
            }
        }
        if (body.includedBrds() == null || body.includedBrds() < 0) {
            throw ApiException.validation("Enter the BRDs included per month (0 or more).");
        }
        UUID id;
        try {
            id = tx.execute(s -> jdbc.sql("""
                    INSERT INTO app.billing_plan (name, currency, monthly_fee, included_brds, price_small, price_medium,
                                                  price_large, proposed_by)
                    VALUES (:name, :currency, :fee, :included, :small, :medium, :large, :me) RETURNING id
                    """).param("name", name).param("currency", body.currency()).param("fee", body.monthlyFee())
                    .param("included", body.includedBrds()).param("small", body.priceSmall())
                    .param("medium", body.priceMedium()).param("large", body.priceLarge()).param("me", admin())
                    .query(UUID.class).single());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("PLAN_NAME_TAKEN", "A plan with this name already exists.");
        }
        audit("BILLING_PLAN_PROPOSED", "BILLING_PLAN", id.toString(), null);
        return plan(id);
    }

    Plan decidePlan(UUID id, String action) {
        tx.executeWithoutResult(s -> {
            UUID me = admin();
            Plan plan = plan(id);
            int updated = switch (action) {
                case "approve" -> {
                    if (me.equals(plan.proposedBy())) {
                        throw new ApiException(HttpStatus.FORBIDDEN, "SECOND_APPROVER_REQUIRED",
                                "A different Garuda Admin must approve this plan.");
                    }
                    yield jdbc.sql("""
                            UPDATE app.billing_plan SET status = 'ACTIVE', approved_by = :me, approved_at = clock_timestamp()
                            WHERE id = :id AND status = 'PROPOSED'
                            """).param("me", me).param("id", id).update();
                }
                case "reject" -> jdbc.sql("UPDATE app.billing_plan SET status = 'REJECTED' WHERE id = :id AND status = 'PROPOSED'")
                        .param("id", id).update();
                case "retire" -> jdbc.sql("""
                        UPDATE app.billing_plan SET status = 'RETIRED', retired_by = :me, retired_at = clock_timestamp()
                        WHERE id = :id AND status = 'ACTIVE'
                        """).param("me", me).param("id", id).update();
                default -> throw ApiException.notFound();
            };
            if (updated != 1) {
                throw ApiException.conflict("PLAN_STATE", "This plan cannot be changed in its current state.");
            }
        });
        audit("BILLING_PLAN_" + action.toUpperCase(java.util.Locale.ROOT), "BILLING_PLAN", id.toString(), null);
        return plan(id);
    }

    // --- projects -------------------------------------------------------------------------------------------

    /** Pending projects of every active organisation, each with its open Garuda decision, if any. */
    List<PendingProject> pendingProjects() {
        List<Object[]> tenants = tx.execute(s -> {
            admin();
            return jdbc.sql("SELECT id, name FROM app.tenant WHERE status = 'ACTIVE' ORDER BY lower(name)")
                    .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}).list();
        });
        List<PendingProject> result = new ArrayList<>();
        for (Object[] t : tenants) {
            UUID tenantId = (UUID) t[0];
            result.addAll(TenantContext.callAs(tenantId, () -> tx.execute(s -> jdbc.sql("""
                    SELECT p.id, p.name, p.description, p.status, p.created_at,
                           (SELECT count(*) FROM app.project_member m WHERE m.project_id = p.id AND m.removed_at IS NULL)
                    FROM app.project p WHERE p.status = 'PENDING' ORDER BY p.created_at
                    """).query((rs, n) -> new PendingProject(tenantId, (String) t[1], rs.getObject(1, UUID.class),
                    rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class), rs.getInt(6),
                    null)).list())));
        }
        return result.stream().map(p -> new PendingProject(p.tenantId(), p.organisationName(), p.projectId(), p.name(),
                p.description(), p.status(), p.createdAt(), p.members(), openDecision(p.projectId()).orElse(null))).toList();
    }

    Decision proposeDecision(NewDecision body) {
        if (body.tenantId() == null || body.projectId() == null) {
            throw ApiException.validation("Choose a project.");
        }
        if (!"ACTIVATE".equals(body.decision()) && !"DECLINE".equals(body.decision())) {
            throw ApiException.validation("Choose ACTIVATE or DECLINE.");
        }
        if ("ACTIVATE".equals(body.decision()) && body.planId() == null) {
            throw ApiException.validation("Choose the billing plan.");
        }
        String note = body.note() == null ? null : body.note().strip();
        if (note != null && note.length() > 2000) {
            throw ApiException.validation("The note is too long.");
        }
        String status = TenantContext.callAs(body.tenantId(), () -> tx.execute(s -> jdbc.sql(
                "SELECT status FROM app.project WHERE id = :id").param("id", body.projectId()).query(String.class)
                .optional().orElse(null)));
        if (status == null) {
            throw ApiException.notFound();
        }
        if (!"PENDING".equals(status)) {
            throw ApiException.conflict("PROJECT_NOT_PENDING", "This project has already been decided.");
        }
        UUID id;
        try {
            id = tx.execute(s -> jdbc.sql("""
                    INSERT INTO app.project_activation (subject_tenant_id, project_id, decision, plan_id, note, requested_by)
                    VALUES (:t, :p, :d, :plan, :note, :me) RETURNING id
                    """).param("t", body.tenantId()).param("p", body.projectId()).param("d", body.decision())
                    .param("plan", "ACTIVATE".equals(body.decision()) ? body.planId() : null).param("note", note)
                    .param("me", admin()).query(UUID.class).single());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DECISION_OPEN", "A decision on this project is already waiting for approval.");
        } catch (DataAccessException e) {
            throw ApiException.conflict("DECISION_REFUSED", firstLine(e));
        }
        audit("PROJECT_" + body.decision() + "_PROPOSED", "PROJECT", body.projectId().toString(), body.tenantId());
        return decision(id);
    }

    Decision decide(UUID id, String action) {
        Object[] row = tx.execute(s -> jdbc.sql(
                "SELECT subject_tenant_id, project_id, decision, plan_id, requested_by, status FROM app.project_activation WHERE id = :id")
                .param("id", id).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getString(6)})
                .optional().orElseThrow(ApiException::notFound));
        UUID tenantId = (UUID) row[0];
        UUID projectId = (UUID) row[1];
        try {
            TenantContext.callAs(tenantId, () -> tx.execute(s -> {
                UUID me = admin();
                if (!"REQUESTED".equals(row[5])) {
                    throw ApiException.conflict("REQUEST_STATE", "This decision has already been made.");
                }
                switch (action) {
                    case "approve" -> {
                        if (me.equals(row[4])) {
                            throw new ApiException(HttpStatus.FORBIDDEN, "SECOND_APPROVER_REQUIRED",
                                    "A different Garuda Admin must approve this decision.");
                        }
                        jdbc.sql("""
                                UPDATE app.project_activation SET status = 'EXECUTED', approved_by = :me,
                                       executed_at = clock_timestamp()
                                WHERE id = :id AND status = 'REQUESTED'
                                """).param("me", me).param("id", id).update();
                        int updated = jdbc.sql("""
                                UPDATE app.project SET status = :status, plan_id = :plan WHERE id = :id AND status = 'PENDING'
                                """).param("status", "ACTIVATE".equals(row[2]) ? "ACTIVE" : "DECLINED")
                                .param("plan", row[3]).param("id", projectId).update();
                        if (updated != 1) {
                            throw ApiException.conflict("PROJECT_NOT_PENDING", "This project is no longer pending.");
                        }
                    }
                    case "reject", "cancel" -> {
                        if ("cancel".equals(action) && !me.equals(row[4])) {
                            throw ApiException.conflict("REQUEST_STATE", "Only the proposer can cancel.");
                        }
                        jdbc.sql("""
                                UPDATE app.project_activation SET status = :status, decided_by = :me, decided_at = clock_timestamp()
                                WHERE id = :id AND status = 'REQUESTED'
                                """).param("status", "reject".equals(action) ? "REJECTED" : "CANCELLED").param("me", me)
                                .param("id", id).update();
                    }
                    default -> throw ApiException.notFound();
                }
                return null;
            }));
        } catch (DataAccessException e) {
            throw ApiException.conflict("DECISION_REFUSED", firstLine(e));
        }
        audit("PROJECT_DECISION_" + action.toUpperCase(java.util.Locale.ROOT), "PROJECT", projectId.toString(), tenantId);
        return decision(id);
    }

    // --- report ---------------------------------------------------------------------------------------------

    record BrdLine(UUID tenantId, String organisationName, UUID projectId, String projectName, UUID requirementId,
                   String requirementKey, String size, String sizingMethod, boolean included, String currency,
                   BigDecimal listPrice, BigDecimal charge, LocalDate acceptedOn) {
    }

    record FullReport(String month, List<ReportLine> lines, List<BrdLine> brds, List<Total> totals) {
    }

    /** Project fees (prorated by active days) and accepted BRDs (allowance first, then size price) for a month. */
    FullReport report(YearMonth month) {
        List<Object[]> tenants = tenants();
        List<ReportLine> lines = new ArrayList<>();
        List<BrdLine> brds = new ArrayList<>();
        for (Object[] t : tenants) {
            UUID tenantId = (UUID) t[0];
            projectFees(tenantId, month).forEach(l -> lines.add(new ReportLine(tenantId, (String) t[1], l.projectId(),
                    l.projectName(), l.planName(), l.currency(), l.monthlyFee(), l.billedFrom(), l.billedTo(),
                    l.activeDays(), l.daysInMonth(), l.projectFee())));
            brdCharges(tenantId, month).forEach(b -> brds.add(new BrdLine(tenantId, (String) t[1], b.projectId(),
                    b.projectName(), b.requirementId(), b.requirementKey(), b.size(), b.sizingMethod(), b.included(),
                    b.currency(), b.listPrice(), b.charge(), b.acceptedOn())));
        }
        List<Map.Entry<String, BigDecimal>> amounts = new ArrayList<>();
        lines.forEach(l -> amounts.add(Map.entry(l.currency(), l.projectFee())));
        brds.forEach(b -> amounts.add(Map.entry(b.currency(), b.charge())));
        return new FullReport(month.toString(), lines, brds, totals(amounts));
    }

    /** One organisation's prorated project fees; no caller check (the caller is a Garuda Admin or the org's admin). */
    List<ReportLine> projectFees(UUID tenantId, YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        int days = month.lengthOfMonth();
        return TenantContext.callAs(tenantId, () -> tx.execute(s -> jdbc.sql("""
                SELECT p.id, p.name, b.name, b.currency, b.monthly_fee, p.activated_at, p.archived_at
                FROM app.project p JOIN app.billing_plan b ON b.id = p.plan_id
                WHERE p.activated_at IS NOT NULL ORDER BY lower(p.name)
                """).query((rs, n) -> {
                    LocalDate from = rs.getObject(6, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
                    OffsetDateTime archived = rs.getObject(7, OffsetDateTime.class);
                    LocalDate to = archived == null ? last : archived.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
                    LocalDate start = from.isAfter(first) ? from : first;
                    LocalDate end = to.isBefore(last) ? to : last;
                    if (end.isBefore(start)) {
                        return null;
                    }
                    int active = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
                    BigDecimal fee = rs.getBigDecimal(5);
                    BigDecimal amount = fee.multiply(BigDecimal.valueOf(active))
                            .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
                    return new ReportLine(tenantId, null, rs.getObject(1, UUID.class), rs.getString(2),
                            rs.getString(3), rs.getString(4), fee, start, end, active, days, amount);
                }).list().stream().filter(java.util.Objects::nonNull).toList()));
    }

    /** One organisation's accepted BRDs billed in the month. */
    List<BrdLine> brdCharges(UUID tenantId, YearMonth month) {
        return TenantContext.callAs(tenantId, () -> tx.execute(s -> jdbc.sql("""
                SELECT q.project_id, p.name, q.requirement_id, r.key, q.size, q.sizing_method, q.included, q.currency,
                       q.list_price, q.charge, q.accepted_at
                FROM app.brd_quote q JOIN app.project p ON p.id = q.project_id JOIN app.requirement r ON r.id = q.requirement_id
                WHERE q.status = 'ACCEPTED' AND q.billing_month = :m ORDER BY q.accepted_at
                """).param("m", month.atDay(1)).query((rs, n) -> new BrdLine(tenantId, null, rs.getObject(1, UUID.class),
                rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getBoolean(7), rs.getString(8), rs.getBigDecimal(9), rs.getBigDecimal(10),
                rs.getObject(11, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate())).list()));
    }

    static List<Total> totals(List<Map.Entry<String, BigDecimal>> amounts) {
        return amounts.stream().collect(java.util.stream.Collectors.groupingBy(Map.Entry::getKey, java.util.TreeMap::new,
                        java.util.stream.Collectors.reducing(BigDecimal.ZERO, Map.Entry::getValue, BigDecimal::add)))
                .entrySet().stream().map(e -> new Total(e.getKey(), e.getValue())).toList();
    }

    String reportCsv(YearMonth month) {
        FullReport report = report(month);
        StringBuilder csv = new StringBuilder("line_type,organisation,project,plan,currency,monthly_fee,billed_from,billed_to,"
                + "active_days,days_in_month,requirement,brd_size,sizing,included_in_allowance,list_price,amount\n");
        for (ReportLine l : report.lines()) {
            csv.append(String.join(",", "PROJECT_FEE", quote(l.organisationName()), quote(l.projectName()),
                    quote(l.planName()), l.currency(), l.monthlyFee().toPlainString(), l.billedFrom().toString(),
                    l.billedTo().toString(), String.valueOf(l.activeDays()), String.valueOf(l.daysInMonth()), "", "", "",
                    "", "", l.projectFee().toPlainString())).append('\n');
        }
        for (BrdLine b : report.brds()) {
            csv.append(String.join(",", "BRD", quote(b.organisationName()), quote(b.projectName()), "", b.currency(), "",
                    b.acceptedOn().toString(), b.acceptedOn().toString(), "", "", b.requirementKey(), b.size(),
                    b.sizingMethod() + " (estimated)", String.valueOf(b.included()), b.listPrice().toPlainString(),
                    b.charge().toPlainString())).append('\n');
        }
        return csv.toString();
    }

    // --- AI cost (GD-22) ------------------------------------------------------------------------------------

    record CostRow(UUID tenantId, String organisationName, UUID projectId, String projectName, UUID requirementId,
                   String requirementKey, long calls, long inputTokens, long outputTokens, BigDecimal costUsd,
                   long estimatedCalls, long unmeteredCalls) {
    }

    record CostReport(String month, String level, List<CostRow> rows, BigDecimal totalUsd, boolean includesEstimates) {
    }

    /**
     * Model spend in USD for a month, by organisation (no tenantId), by project of one organisation, or by requirement
     * of one project, from the daily rollup (the API never reads raw model-call records). Calls with locally
     * estimated usage and calls without usage data are counted separately; the latter add no cost.
     */
    CostReport aiCosts(YearMonth month, UUID tenantId, UUID projectId) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);
        String level = tenantId == null ? "ORGANISATION" : projectId == null ? "PROJECT" : "REQUIREMENT";
        List<Object[]> scope = tenantId == null ? tenants()
                : tenants().stream().filter(t -> t[0].equals(tenantId)).toList();
        if (scope.isEmpty()) {
            throw ApiException.notFound();
        }
        String group = switch (level) {
            case "ORGANISATION" -> "NULL::uuid, NULL::text, NULL::uuid, NULL::text";
            case "PROJECT" -> "p.id, p.name, NULL::uuid, NULL::text";
            default -> "p.id, p.name, r.id, r.key";
        };
        String sql = "SELECT " + group + """
                , coalesce(sum(c.calls), 0), coalesce(sum(c.input_tokens), 0), coalesce(sum(c.output_tokens), 0),
                       coalesce(sum(c.cost_usd), 0), coalesce(sum(c.estimated_calls), 0), coalesce(sum(c.unmetered_calls), 0)
                FROM app.ai_cost_daily c
                LEFT JOIN app.requirement r ON r.id = c.requirement_id
                LEFT JOIN app.project p ON p.id = c.project_id
                WHERE c.day >= :from AND c.day < :to
                """ + (projectId == null ? "" : " AND c.project_id = :project") + " GROUP BY 1, 2, 3, 4 ORDER BY 8 DESC";
        List<CostRow> rows = new ArrayList<>();
        for (Object[] t : scope) {
            UUID tenant = (UUID) t[0];
            rows.addAll(TenantContext.callAs(tenant, () -> tx.execute(s -> {
                var statement = jdbc.sql(sql).param("from", from).param("to", to);
                if (projectId != null) {
                    statement = statement.param("project", projectId);
                }
                return statement.query((rs, n) -> new CostRow(tenant, (String) t[1], rs.getObject(1, UUID.class),
                        rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getLong(5), rs.getLong(6),
                        rs.getLong(7), rs.getBigDecimal(8), rs.getLong(9), rs.getLong(10))).list();
            })));
        }
        if ("ORGANISATION".equals(level)) {
            rows = rows.stream().filter(r -> r.calls() > 0).toList();
        }
        BigDecimal total = rows.stream().map(CostRow::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CostReport(month.toString(), level, rows, total,
                rows.stream().anyMatch(r -> r.estimatedCalls() > 0 || r.unmeteredCalls() > 0));
    }

    private List<Object[]> tenants() {
        return tx.execute(s -> {
            admin();
            return jdbc.sql("SELECT id, name FROM app.tenant ORDER BY lower(name)")
                    .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}).list();
        });
    }

    // --- internals ------------------------------------------------------------------------------------------

    private UUID admin() {
        return staffAccess.requirePlatformCapability(StaffCapability.ORG_ONBOARD);
    }

    private void audit(String action, String type, String ref, UUID tenant) {
        UUID actor = TenantContext.currentActor().orElse(null);
        auditTx.executeWithoutResult(s -> jdbc.sql("""
                INSERT INTO app.platform_audit_event (actor_staff_id, action, target_type, target_ref, tenant_ref, result,
                                                      correlation_id)
                VALUES (:actor, :action, :type, :ref, :tenant, 'SUCCESS', :correlation)
                """).param("actor", actor).param("action", action).param("type", type).param("ref", ref)
                .param("tenant", tenant).param("correlation", CorrelationId.current().orElse(null)).update());
    }

    private static final String PLAN_SELECT = """
            SELECT p.id, p.name, p.currency, p.monthly_fee, p.included_brds, p.price_small, p.price_medium, p.price_large,
                   p.status, p.proposed_by, pr.display_name, p.proposed_at, p.approved_by, ap.display_name, p.approved_at
            FROM app.billing_plan p JOIN app.staff_user pr ON pr.id = p.proposed_by
            LEFT JOIN app.staff_user ap ON ap.id = p.approved_by
            """;

    private Plan plan(UUID id) {
        return tx.execute(s -> jdbc.sql(PLAN_SELECT + " WHERE p.id = :id").param("id", id).query((rs, n) -> plan(rs))
                .optional().orElseThrow(ApiException::notFound));
    }

    private static Plan plan(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Plan(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getInt(5),
                rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9), rs.getObject(10, UUID.class),
                rs.getString(11), rs.getObject(12, OffsetDateTime.class), rs.getObject(13, UUID.class), rs.getString(14),
                rs.getObject(15, OffsetDateTime.class));
    }

    private static final String DECISION_SELECT = """
            SELECT a.id, a.decision, a.plan_id, b.name, a.note, a.status, a.requested_by, s.display_name, a.requested_at,
                   a.approved_by, a.executed_at
            FROM app.project_activation a JOIN app.staff_user s ON s.id = a.requested_by
            LEFT JOIN app.billing_plan b ON b.id = a.plan_id
            """;

    private Decision decision(UUID id) {
        return tx.execute(s -> jdbc.sql(DECISION_SELECT + " WHERE a.id = :id").param("id", id)
                .query((rs, n) -> decision(rs)).optional().orElseThrow(ApiException::notFound));
    }

    private Optional<Decision> openDecision(UUID projectId) {
        return tx.execute(s -> jdbc.sql(DECISION_SELECT + " WHERE a.project_id = :p AND a.status = 'REQUESTED'")
                .param("p", projectId).query((rs, n) -> decision(rs)).optional());
    }

    private static Decision decision(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Decision(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8),
                rs.getObject(9, OffsetDateTime.class), rs.getObject(10, UUID.class), rs.getObject(11, OffsetDateTime.class));
    }

    private static String firstLine(DataAccessException e) {
        return Optional.ofNullable(e.getMostSpecificCause().getMessage()).map(m -> m.split("\n")[0].replace("ERROR: ", ""))
                .orElse("refused");
    }

    private static String quote(String value) {
        return value == null ? "" : "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
