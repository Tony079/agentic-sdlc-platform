package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.common.api.MailSender;
import io.github.tony079.sdlc.common.api.MailSender.MailException;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

/**
 * GD-25: emails each new in-app notification to its recipient. Runs in the API process (only it may read members'
 * email addresses), one tenant at a time under row-level security. An email names the requirement and links to it;
 * it never carries BRD, clarification or document content. Members who turned email off are skipped; a failed
 * delivery is retried on the next runs (three attempts); notifications older than a day are not emailed late.
 */
@Component
@EnableScheduling
@Profile("!migrate & !provision")
@ConditionalOnProperty(prefix = "sdlc.worker", name = "enabled", havingValue = "false", matchIfMissing = true)
class NotificationMailer {

    private static final Logger log = LoggerFactory.getLogger(NotificationMailer.class);
    static final int MAX_ATTEMPTS = 3;
    private static final int BATCH = 50;

    record Pending(UUID id, String kind, UUID requirementId, String email, String name, boolean wantsEmail,
                   String requirementKey, String title) {
    }

    private record Template(String subject, String heading, String body, String action, String path) {
    }

    private final MailSender mail;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final String baseUrl;

    NotificationMailer(MailSender mail, JdbcClient jdbc, PlatformTransactionManager transactionManager,
                       @Value("${sdlc.public-base-url:http://localhost:8080}") String baseUrl) {
        this.mail = mail;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Scheduled(fixedDelayString = "${sdlc.notifications.email-interval:30s}", initialDelayString = "15s")
    void run() {
        if (!mail.configured()) {
            return; // in-app only; pending rows wait (and age out after a day)
        }
        List<UUID> tenants = jdbc.sql("SELECT id FROM app.tenant WHERE status = 'ACTIVE'").query(UUID.class).list();
        for (UUID tenant : tenants) {
            try {
                deliver(tenant);
            } catch (RuntimeException e) {
                log.warn("notification email run failed for one organisation: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** Sends the tenant's pending notification emails; returns how many were sent. */
    int deliver(UUID tenant) {
        return TenantContext.callAs(tenant, () -> tx.execute(s -> {
            jdbc.sql("""
                    UPDATE app.notification SET email_status = 'SKIPPED'
                    WHERE email_status = 'PENDING' AND created_at < clock_timestamp() - interval '1 day'
                    """).update();
            List<Pending> pending = jdbc.sql("""
                    SELECT n.id, n.kind, n.requirement_id, u.email, u.display_name,
                           u.email_notifications AND u.status = 'ACTIVE', r.key, rv.title
                    FROM app.notification n
                    JOIN app.app_user u ON u.id = n.recipient_id
                    LEFT JOIN app.requirement r ON r.id = n.requirement_id
                    LEFT JOIN app.requirement_revision rv ON rv.requirement_id = r.id
                         AND rv.revision_no = r.current_revision_no
                    WHERE n.email_status = 'PENDING'
                    ORDER BY n.created_at LIMIT :batch
                    FOR UPDATE OF n SKIP LOCKED
                    """).param("batch", BATCH).query((rs, i) -> new Pending(rs.getObject(1, UUID.class),
                            rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                            rs.getBoolean(6), rs.getString(7), rs.getString(8))).list();
            int sent = 0;
            for (Pending p : pending) {
                if (!p.wantsEmail()) {
                    record(p.id(), "SKIPPED", false);
                    continue;
                }
                try {
                    Template t = template(p);
                    mail.send(p.email(), t.subject(), text(p, t), html(p, t));
                    record(p.id(), "SENT", true);
                    sent++;
                } catch (MailException | RuntimeException e) {
                    log.warn("notification email not delivered: {}",
                            e instanceof MailException m ? m.code() : e.getClass().getSimpleName());
                    jdbc.sql("""
                            UPDATE app.notification SET email_attempts = email_attempts + 1,
                                   email_status = CASE WHEN email_attempts + 1 >= :max THEN 'FAILED' ELSE 'PENDING' END
                            WHERE id = :id
                            """).param("max", MAX_ATTEMPTS).param("id", p.id()).update();
                }
            }
            if (sent > 0) {
                log.info("notification emails sent: {}", sent);
            }
            return sent;
        }));
    }

    private void record(UUID id, String status, boolean attempted) {
        jdbc.sql("""
                UPDATE app.notification SET email_status = :s, emailed_at = CASE WHEN :a THEN clock_timestamp() END,
                       email_attempts = email_attempts + CASE WHEN :a THEN 1 ELSE 0 END
                WHERE id = :id
                """).param("s", status).param("a", attempted).param("id", id).update();
    }

    private static final Map<String, String[]> KINDS = Map.ofEntries(
            Map.entry("TDD_HANDOVER", new String[] {"Technical design to start: %s", "A BRD is ready for technical design",
                    "The BRD for %s was approved and handed to you as Technical Approver. Start the technical design "
                            + "when you are ready.", "Start the design", null}),
            Map.entry("TDD_NO_TECHNICAL_APPROVER", new String[] {"No Technical Approver for %s",
                    "A project needs a Technical Approver", "The BRD for %s was approved, but its project has no "
                            + "Technical Approver to start the technical design. Assign one on the project's Members tab.",
                    "Open the requirement", null}),
            Map.entry("TDD_READY_FOR_REVIEW", new String[] {"Technical design ready for your review: %s",
                    "Your technical design is ready", "The Architect agent finished the technical design for %s. "
                            + "Review it, then confirm it or request changes.", "Review the design", null}),
            Map.entry("TDD_AWAITING_APPROVAL", new String[] {"Technical design waiting for your approval: %s",
                    "A technical design needs your approval", "The author confirmed the technical design for %s. As a "
                            + "BRD Approver, give the final approval or request changes.", "Open the design", null}),
            Map.entry("TDD_APPROVED", new String[] {"Technical design approved: %s", "The technical design was approved",
                    "The technical design for %s was approved. You can download it as PDF or Word.", "Open the design",
                    null}),
            Map.entry("TDD_FAILED", new String[] {"Technical design could not be written: %s",
                    "The technical design could not be written", "The Architect agent could not produce the technical "
                            + "design for %s. You can try again from the requirement page.", "Try again", null}),
            // GD-33: estimation. Only counts and links, never figures (estimates are for Estimators and BRD Approvers).
            Map.entry("ESTIMATE_HANDOVER", new String[] {"Estimate to start: %s", "A technical design is ready to estimate",
                    "The technical design for %s was approved and handed to you for estimation.", "Start the estimate",
                    null}),
            Map.entry("ESTIMATE_NO_ESTIMATOR", new String[] {"No Estimator for %s", "A project needs an Estimator",
                    "The technical design for %s was approved, but its project has no Estimator. Assign one so the "
                            + "estimate can start.", "Open the project", null}),
            Map.entry("ESTIMATE_READY_FOR_REVIEW", new String[] {"Estimate ready for your review: %s",
                    "An estimate is ready for review", "The Estimation agent has written an estimate for %s. Review and "
                            + "adjust it before a BRD Approver approves it.", "Review the estimate", null}),
            Map.entry("ESTIMATE_APPROVED", new String[] {"Estimate approved: %s", "The estimate was approved",
                    "The estimate for %s was approved. You can download it as PDF, Word or a spreadsheet.",
                    "Open the estimate", null}),
            Map.entry("ESTIMATE_FAILED", new String[] {"Estimate could not be written: %s",
                    "The estimate could not be written", "The Estimation agent could not produce the estimate for %s. "
                            + "You can try again from the requirement page.", "Try again", null}),
            // GD-34: delivery plan.
            Map.entry("PLAN_HANDOVER", new String[] {"Delivery plan to start: %s", "An estimate is ready to plan",
                    "The estimate for %s was approved and handed to you for the delivery plan.", "Start the plan", null}),
            Map.entry("PLAN_NO_ESTIMATOR", new String[] {"No Estimator to plan %s", "A project needs an Estimator",
                    "The estimate for %s was approved, but its project has no Estimator to plan it. Assign one.",
                    "Open the project", null}),
            Map.entry("PLAN_READY_FOR_REVIEW", new String[] {"Delivery plan ready for your review: %s",
                    "A delivery plan is ready for review", "The Planning agent has written the delivery plan for %s. "
                            + "Review and edit it before a BRD Approver approves it.", "Review the plan", null}),
            Map.entry("PLAN_APPROVED", new String[] {"Delivery plan approved: %s", "The delivery plan was approved",
                    "The delivery plan for %s was approved and is visible to the project.", "Open the plan", null}),
            Map.entry("PLAN_FAILED", new String[] {"Delivery plan could not be written: %s",
                    "The delivery plan could not be written", "The Planning agent could not produce the delivery plan "
                            + "for %s. You can try again from the requirement page.", "Try again", null}),
            Map.entry("CLARIFICATION_ASSIGNED", new String[] {"Questions about %s need your answer",
                    "The BA agent has questions for you",
                    "The BA agent needs your answers about %s before it can finish the BRD. The analysis continues "
                            + "as soon as you answer.", "Answer the questions", "/clarifications"}),
            Map.entry("BRD_READY_FOR_REVIEW", new String[] {"BRD ready for your review: %s", "Your BRD is ready",
                    "The business requirements document for %s is ready. Review it, then confirm it or request "
                            + "changes.", "Review the BRD", null}),
            Map.entry("BRD_AWAITING_APPROVAL", new String[] {"BRD waiting for your approval: %s", "A BRD needs your approval",
                    "The author confirmed the business requirements document for %s. As a BRD Approver, give the "
                            + "final approval or request changes.", "Open the BRD", null}),
            Map.entry("BRD_APPROVED", new String[] {"BRD approved: %s", "The BRD was approved",
                    "The business requirements document for %s was approved and is being published to the project.",
                    "View the requirement", null}),
            Map.entry("BRD_RELEASED", new String[] {"BRD published: %s", "The approved BRD is available",
                    "The approved business requirements document for %s is available to the project. You can "
                            + "download it as PDF or Word.", "Open the BRD", null}),
            Map.entry("BRD_PUBLISH_BLOCKED", new String[] {"BRD not published yet: %s", "Publishing is waiting",
                    "The approved BRD for %s could not be published yet because an automatic check did not pass.",
                    "See why", null}),
            Map.entry("BRD_NOT_FEASIBLE", new String[] {"Requirement not feasible: %s", "The requirement was found not feasible",
                    "The BA agent found %s not feasible. The requirement is closed and its BRD charge is waived. "
                            + "The reasons are on the requirement page.", "See the reasons", null}));

    private Template template(Pending p) {
        String subject = p.requirementKey() == null ? "the requirement" : p.requirementKey();
        String named = p.requirementKey() == null ? "your requirement"
                : p.requirementKey() + (p.title() == null ? "" : " “" + p.title() + "”");
        String[] k = KINDS.get(p.kind());
        String requirementPath = p.requirementId() == null ? "/notifications" : "/requirements/" + p.requirementId();
        if (k == null) {
            return new Template("Update on " + subject, "There is an update for you",
                    "There is an update on " + named + ".", "Open Garuda", requirementPath);
        }
        return new Template(k[0].formatted(subject), k[1], k[2].formatted(named), k[3],
                k[4] == null ? requirementPath : k[4]);
    }

    private String text(Pending p, Template t) {
        return "Hello " + p.name() + ",\n\n" + t.body() + "\n\n" + t.action() + ": " + baseUrl + t.path()
                + "\n\nYou receive this because you are a member of this project on Garuda. You can turn these emails "
                + "off on the Notifications page.\n\nGaruda";
    }

    private String html(Pending p, Template t) {
        return """
                <!doctype html><html><body style="margin:0;background:#f4f5f7;font-family:-apple-system,Segoe UI,\
                Roboto,Helvetica,Arial,sans-serif;color:#1f2430">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="padding:32px 12px">
                <tr><td align="center">
                <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;\
                background:#ffffff;border:1px solid #e3e5ea;border-radius:10px">
                <tr><td style="padding:20px 28px;border-bottom:1px solid #eceef2;font-weight:700;font-size:16px;\
                letter-spacing:.04em;color:#1e3a8a">GARUDA</td></tr>
                <tr><td style="padding:28px">
                <h1 style="margin:0 0 12px;font-size:20px">%s</h1>
                <p style="margin:0 0 8px;font-size:15px;line-height:1.5">Hello %s,</p>
                <p style="margin:0 0 24px;font-size:15px;line-height:1.5">%s</p>
                <a href="%s" style="display:inline-block;background:#1e40af;color:#ffffff;text-decoration:none;\
                padding:11px 20px;border-radius:6px;font-weight:600;font-size:15px">%s</a>
                <p style="margin:24px 0 0;font-size:13px;line-height:1.5;color:#5b6170">You receive this because you \
                are a member of this project on Garuda. You can turn these emails off on the Notifications page.</p>
                </td></tr></table></td></tr></table></body></html>
                """.formatted(HtmlUtils.htmlEscape(t.heading()), HtmlUtils.htmlEscape(p.name()),
                HtmlUtils.htmlEscape(t.body()), HtmlUtils.htmlEscape(baseUrl + t.path()),
                HtmlUtils.htmlEscape(t.action()));
    }
}
