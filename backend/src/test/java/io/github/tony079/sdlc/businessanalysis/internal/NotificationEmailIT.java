package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.tony079.sdlc.common.api.MailSender;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * GD-25: notifications are emailed by the API process with a link only (no BRD content); a member can turn email
 * off; failures are retried three times; old notifications are not emailed late.
 */
class NotificationEmailIT extends ApiIntegrationTest {

    private final JdbcTemplate admin = TestDatabase.get().admin();

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    record Mail(String to, String subject, String text, String html) {
    }

    /** Records messages; fails while {@code failing} is set. */
    static final class RecordingSender implements MailSender {
        final List<Mail> sent = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public void send(String to, String subject, String textBody, String htmlBody) throws MailException {
            if (failing) {
                throw new MailException("MAIL_UNREACHABLE");
            }
            sent.add(new Mail(to, subject, textBody, htmlBody));
        }
    }

    private UUID tenant() {
        return admin.queryForObject("SELECT tenant_id FROM app.app_user WHERE email = 'alice@acme.test'", UUID.class);
    }

    private UUID notify(String email, String kind) {
        return admin.queryForObject("""
                INSERT INTO app.notification (tenant_id, recipient_id, kind)
                SELECT tenant_id, id, ? FROM app.app_user WHERE email = ? RETURNING id
                """, UUID.class, kind, email);
    }

    private String status(UUID id) {
        return admin.queryForObject("SELECT email_status FROM app.notification WHERE id = ?", String.class, id);
    }

    @Test
    void notificationsAreEmailedOnceWithALinkAndMembersCanTurnEmailOff() {
        RecordingSender sender = new RecordingSender();
        NotificationMailer mailer = new NotificationMailer(sender, jdbc, transactionManager, "https://garuda.example.test/");
        mailer.deliver(tenant()); // clear anything pending from other tests
        sender.sent.clear();

        UUID first = notify("alice@acme.test", "CLARIFICATION_ASSIGNED");
        assertThat(mailer.deliver(tenant())).isEqualTo(1);
        assertThat(status(first)).isEqualTo("SENT");
        Mail mail = sender.sent.getFirst();
        assertThat(mail.to()).isEqualTo("alice@acme.test");
        assertThat(mail.subject()).contains("need your answer");
        assertThat(mail.text()).contains("https://garuda.example.test/clarifications");
        assertThat(mail.html()).contains("Answer the questions");
        // Sent once only.
        assertThat(mailer.deliver(tenant())).isZero();

        // The member turns email off: in-app only from then on.
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        assertThat(alice.get("/api/v1/notifications/settings").body().path("email").asBoolean()).isTrue();
        assertThat(alice.put("/api/v1/notifications/settings", Map.of("email", false)).body().path("email")
                .asBoolean()).isFalse();
        UUID quiet = notify("alice@acme.test", "BRD_READY_FOR_REVIEW");
        mailer.deliver(tenant());
        assertThat(status(quiet)).isEqualTo("SKIPPED");
        assertThat(sender.sent).hasSize(1);
        alice.put("/api/v1/notifications/settings", Map.of("email", true));

        // A failing server: retried, then FAILED after three attempts.
        sender.failing = true;
        UUID failing = notify("alice@acme.test", "BRD_AWAITING_APPROVAL");
        mailer.deliver(tenant());
        assertThat(status(failing)).isEqualTo("PENDING");
        mailer.deliver(tenant());
        mailer.deliver(tenant());
        assertThat(status(failing)).isEqualTo("FAILED");
        sender.failing = false;

        // Older than a day: not emailed late.
        UUID old = notify("alice@acme.test", "BRD_APPROVED");
        admin.update("UPDATE app.notification SET created_at = now() - interval '2 days' WHERE id = ?", old);
        mailer.deliver(tenant());
        assertThat(status(old)).isEqualTo("SKIPPED");
        assertThat(sender.sent).hasSize(1);
    }
}
