package io.github.tony079.sdlc.identity.internal;

import java.util.UUID;

import io.github.tony079.sdlc.common.api.MailSender;
import io.github.tony079.sdlc.common.api.MailSender.MailException;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

/**
 * Invitation and password-reset email (GD-04). Sent after the creating transaction commits; the outcome is recorded
 * on the invitation (SENT or FAILED) so the administrator sees whether to copy the link instead. Links carry the token
 * in the URL fragment, which browsers do not send to servers or put in Referer headers.
 */
@Component
class AccountMail {

    private static final Logger log = LoggerFactory.getLogger(AccountMail.class);

    enum Kind { INVITE, PASSWORD_RESET }

    record Message(UUID tenantId, UUID invitationId, Kind kind, String organisationName, String organisationSlug,
                   String recipient, String recipientName, String token) {
    }

    private final MailSender mail;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final String baseUrl;

    AccountMail(MailSender mail, JdbcClient jdbc, PlatformTransactionManager transactionManager,
                @Value("${sdlc.public-base-url:http://localhost:8080}") String baseUrl) {
        this.mail = mail;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    boolean configured() {
        return mail.configured();
    }

    String link(Message m) {
        if (m.tenantId() == null) {
            // Garuda staff (GD-15): links to the staff console.
            return baseUrl + "/staff/" + (m.kind() == Kind.INVITE ? "invite#" : "reset#") + m.token();
        }
        return baseUrl + "/o/" + m.organisationSlug() + (m.kind() == Kind.INVITE ? "/invite#" : "/reset#") + m.token();
    }

    /** Sends the message and records the result; never throws. Returns the email status. */
    String send(Message m) {
        if (!mail.configured()) {
            return "NOT_SENT";
        }
        String status;
        try {
            mail.send(m.recipient(), subject(m), text(m), html(m));
            status = "SENT";
        } catch (MailException e) {
            log.warn("account email not delivered: {}", e.code());
            status = "FAILED";
        } catch (RuntimeException e) {
            log.warn("account email not delivered: {}", e.getClass().getSimpleName());
            status = "FAILED";
        }
        String recorded = status;
        if (m.tenantId() == null) {
            tx.execute(s -> jdbc.sql(
                    "UPDATE app.staff_invitation SET email_status = :status WHERE id = :id AND used_at IS NULL")
                    .param("status", recorded).param("id", m.invitationId()).update());
        } else {
            TenantContext.callAs(m.tenantId(), () -> tx.execute(s -> jdbc.sql(
                    "UPDATE app.user_invitation SET email_status = :status WHERE id = :id AND used_at IS NULL")
                    .param("status", recorded).param("id", m.invitationId()).update()));
        }
        return status;
    }

    private String subject(Message m) {
        return m.kind() == Kind.INVITE
                ? (m.tenantId() == null ? "You are invited to the Garuda console" : "You are invited to " + m.organisationName() + " on Garuda")
                : "Reset your Garuda password";
    }

    private String text(Message m) {
        String intro = m.kind() == Kind.INVITE
                ? "You have been invited to join " + m.organisationName() + " on Garuda.\n\nSet your password here:"
                : "A password reset was requested for your Garuda account at " + m.organisationName()
                        + ".\n\nChoose a new password here:";
        return "Hello " + m.recipientName() + ",\n\n" + intro + "\n" + link(m)
                + "\n\nThe link works once and expires in 72 hours. If you did not expect this email, ignore it."
                + "\n\nGaruda";
    }

    private String html(Message m) {
        String name = HtmlUtils.htmlEscape(m.recipientName());
        String org = HtmlUtils.htmlEscape(m.organisationName());
        String href = HtmlUtils.htmlEscape(link(m));
        String heading = m.kind() == Kind.INVITE ? "Join " + org + " on Garuda" : "Reset your password";
        String body = m.kind() == Kind.INVITE
                ? "You have been invited to join <strong>" + org + "</strong>. Set your password to get started."
                : "A password reset was requested for your account at <strong>" + org + "</strong>.";
        String action = m.kind() == Kind.INVITE ? "Accept invitation" : "Choose a new password";
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
                <p style="margin:24px 0 0;font-size:13px;line-height:1.5;color:#5b6170">The link works once and \
                expires in 72 hours. If you did not expect this email, you can ignore it.</p>
                </td></tr></table></td></tr></table></body></html>
                """.formatted(heading, name, body, href, action);
    }
}
