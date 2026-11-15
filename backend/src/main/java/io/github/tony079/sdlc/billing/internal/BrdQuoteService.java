package io.github.tony079.sdlc.billing.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.ClientUsers;
import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GD-22 for organisation users: the BRD quote of a requirement, accepted or declined by the requirement's owner or a
 * BRD Approver of the project, and the organisation's own monthly charges (Organisation Admins).
 */
@Service
@ConditionalOnWebApplication
class BrdQuoteService {

    record Quote(UUID id, UUID requirementId, String status, String size, String sizingMethod, boolean estimated,
                 int documents, boolean hasRepository, int repositories, int textChars, String currency, BigDecimal listPrice,
                 String planName, int includedPerMonth, int includedUsedThisMonth, boolean wouldBeIncluded,
                 Boolean included, BigDecimal charge, LocalDate billingMonth, OffsetDateTime quotedAt,
                 String acceptedByName, OffsetDateTime acceptedAt, String declinedByName, OffsetDateTime declinedAt,
                 boolean canDecide) {
    }

    record ChargeLine(String type, UUID projectId, String projectName, String requirementKey, String size,
                      Boolean included, String currency, BigDecimal amount, LocalDate date) {
    }

    record Statement(String month, List<ChargeLine> lines, List<BillingService.Total> totals, boolean estimatedSizes) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final BillingService billing;

    BrdQuoteService(JdbcClient jdbc, PlatformTransactionManager transactionManager, OutboxPublisher outbox,
                    AuditWriter audit, BillingService billing) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.outbox = outbox;
        this.audit = audit;
        this.billing = billing;
    }

    /** The requirement's quote; 404 when none was made (no plan, or analysis inputs not ready yet). */
    Quote quote(UUID requirementId) {
        CurrentUser me = ClientUsers.require();
        return tx.execute(s -> load(requirementId, me.userId()));
    }

    Quote decide(UUID requirementId, String action) {
        CurrentUser me = ClientUsers.require();
        if (!"accept".equals(action) && !"decline".equals(action)) {
            throw ApiException.notFound();
        }
        tx.executeWithoutResult(s -> {
            Quote q = load(requirementId, me.userId());
            if (!q.canDecide()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "QUOTE_DECISION_NOT_ALLOWED",
                        "Only the requirement's owner or a BRD Approver of the project can decide on this quote.");
            }
            if ("ACCEPTED".equals(q.status())) {
                throw ApiException.conflict("QUOTE_ACCEPTED", "This quote has already been accepted.");
            }
            if ("decline".equals(action)) {
                if ("DECLINED".equals(q.status())) {
                    return;
                }
                jdbc.sql("""
                        UPDATE app.brd_quote SET status = 'DECLINED', declined_by = :me, declined_at = clock_timestamp()
                        WHERE id = :id AND status = 'PROPOSED'
                        """).param("me", me.userId()).param("id", q.id()).update();
                audit.record(AuditAction.BRD_QUOTE_DECLINED, "REQUIREMENT", requirementId, AuditResult.SUCCESS);
                return;
            }
            UUID project = jdbc.sql("SELECT project_id FROM app.brd_quote WHERE id = :id").param("id", q.id())
                    .query(UUID.class).single();
            // Serialise acceptances of one project so the monthly allowance is never over-used.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:p, 22))").param("p", project.toString())
                    .query((rs, n) -> 1).single();
            LocalDate month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
            int used = used(project, month);
            boolean included = used < q.includedPerMonth();
            jdbc.sql("""
                    UPDATE app.brd_quote SET status = 'ACCEPTED', accepted_by = :me, accepted_at = clock_timestamp(),
                           included = :included, charge = CASE WHEN :included THEN 0 ELSE list_price END, billing_month = :month,
                           declined_by = NULL, declined_at = NULL
                    WHERE id = :id AND status IN ('PROPOSED', 'DECLINED')
                    """).param("me", me.userId()).param("included", included).param("month", month).param("id", q.id())
                    .update();
            outbox.publish("REQUIREMENT", requirementId, EventTypes.BRD_QUOTE_ACCEPTED,
                    Map.of("requirementId", requirementId.toString(), "quoteId", q.id().toString()));
            audit.record(AuditAction.BRD_QUOTE_ACCEPTED, "REQUIREMENT", requirementId, AuditResult.SUCCESS);
        });
        return quote(requirementId);
    }

    /** The organisation's charges for a month: project fees (prorated) and accepted BRDs (Organisation Admins). */
    Statement statement(java.time.YearMonth month) {
        CurrentUser me = ClientUsers.require();
        if (!me.orgAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only Organisation Admins see billing.");
        }
        List<ChargeLine> lines = new java.util.ArrayList<>(billing.projectFees(me.tenantId(), month).stream()
                .map(l -> new ChargeLine("PROJECT_FEE", l.projectId(), l.projectName(), null, null, null, l.currency(),
                        l.projectFee(), l.billedTo())).toList());
        List<BillingService.BrdLine> brds = billing.brdCharges(me.tenantId(), month);
        brds.forEach(b -> lines.add(new ChargeLine("BRD", b.projectId(), b.projectName(), b.requirementKey(), b.size(),
                b.included(), b.currency(), b.charge(), b.acceptedOn())));
        return new Statement(month.toString(), lines, BillingService.totals(lines.stream()
                .map(l -> Map.entry(l.currency(), l.amount())).toList()), !brds.isEmpty());
    }

    // --- internals ------------------------------------------------------------------------------------------

    private int used(UUID project, LocalDate month) {
        return jdbc.sql("""
                SELECT count(*) FROM app.brd_quote
                WHERE project_id = :p AND status = 'ACCEPTED' AND billing_month = :m AND included
                """).param("p", project).param("m", month).query(Integer.class).single();
    }

    private Quote load(UUID requirementId, UUID me) {
        LocalDate month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        Quote q = jdbc.sql("""
                SELECT q.id, q.status, q.size, q.sizing_method, q.documents, q.has_repository, q.repositories, q.text_chars, q.currency,
                       q.list_price, b.name, b.included_brds, q.included, q.charge, q.billing_month, q.quoted_at,
                       au.display_name, q.accepted_at, du.display_name, q.declined_at,
                       (r.created_by = :me OR app.is_brd_approver(q.project_id)), q.project_id
                FROM app.brd_quote q
                JOIN app.requirement r ON r.id = q.requirement_id
                JOIN app.billing_plan b ON b.id = q.plan_id
                LEFT JOIN app.app_user au ON au.id = q.accepted_by
                LEFT JOIN app.app_user du ON du.id = q.declined_by
                WHERE q.requirement_id = :r
                """).param("me", me).param("r", requirementId).query((rs, n) -> new Quote(rs.getObject(1, UUID.class),
                        requirementId, rs.getString(2), rs.getString(3), rs.getString(4), true, rs.getInt(5),
                        rs.getBoolean(6), rs.getInt(7), rs.getInt(8), rs.getString(9), rs.getBigDecimal(10),
                        rs.getString(11), rs.getInt(12), 0, false, (Boolean) rs.getObject(13), rs.getBigDecimal(14),
                        rs.getObject(15, LocalDate.class), rs.getObject(16, OffsetDateTime.class), rs.getString(17),
                        rs.getObject(18, OffsetDateTime.class), rs.getString(19), rs.getObject(20, OffsetDateTime.class),
                        rs.getBoolean(21))).optional()
                .orElseThrow(ApiException::notFound);
        UUID project = jdbc.sql("SELECT project_id FROM app.brd_quote WHERE id = :id").param("id", q.id())
                .query(UUID.class).single();
        int used = used(project, month);
        return new Quote(q.id(), q.requirementId(), q.status(), q.size(), q.sizingMethod(), q.estimated(), q.documents(),
                q.hasRepository(), q.repositories(), q.textChars(), q.currency(), q.listPrice(), q.planName(), q.includedPerMonth(), used,
                used < q.includedPerMonth(), q.included(), q.charge(), q.billingMonth(), q.quotedAt(), q.acceptedByName(),
                q.acceptedAt(), q.declinedByName(), q.declinedAt(), q.canDecide());
    }
}
