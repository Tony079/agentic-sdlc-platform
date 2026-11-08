package io.github.tony079.sdlc.securitymonitoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;

import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * C-17 security alerting (owner decision 2026-11-08) against an in-process webhook receiver and a loopback SMTP server
 * (TEST ONLY): rules, deduplication, thresholds, rate limiting, signed webhook, email, bounded retries, content-free
 * payloads, events kept when delivery fails.
 */
class SecurityAlertingIT {

    private static final String SECRET = "webhook-secret-" + UUID.randomUUID();
    private final JdbcTemplate admin = TestDatabase.get().admin();
    private HttpServer webhook;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> signatures = new CopyOnWriteArrayList<>();
    private final AtomicInteger webhookStatus = new AtomicInteger(200);
    private ServerSocket smtp;
    private final List<String> mails = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        WorkerContext.get();
        // Only this test's events: start the cursor now.
        admin.update("UPDATE app.security_alert_cursor SET last_occurred_at = clock_timestamp(), last_event_id = NULL");
        admin.update("DELETE FROM app.security_alert_delivery");
        admin.update("DELETE FROM app.security_alert");
        webhook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhook.createContext("/alerts", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            signatures.add(exchange.getRequestHeaders().getFirst("X-Sdlc-Signature"));
            exchange.sendResponseHeaders(webhookStatus.get(), -1);
            exchange.close();
        });
        webhook.start();
        smtp = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::serveSmtp);
    }

    @AfterEach
    void stop() throws Exception {
        webhook.stop(0);
        smtp.close();
    }

    private void serveSmtp() {
        while (!smtp.isClosed()) {
            try (Socket s = smtp.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = s.getOutputStream();
                out.write("220 test\r\n".getBytes());
                StringBuilder data = null;
                String line;
                while ((line = in.readLine()) != null) {
                    if (data != null) {
                        if (line.equals(".")) {
                            mails.add(data.toString());
                            data = null;
                            out.write("250 queued\r\n".getBytes());
                        } else {
                            data.append(line).append('\n');
                        }
                        continue;
                    }
                    if (line.startsWith("EHLO")) {
                        out.write("250-test\r\n250 AUTH PLAIN\r\n".getBytes());
                    } else if (line.startsWith("AUTH")) {
                        out.write("235 ok\r\n".getBytes());
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
            } catch (Exception e) {
                return;
            }
        }
    }

    private SecurityAlerting alerting(int maxPerHour) {
        return new SecurityAlerting(WorkerContext.bean(JdbcClient.class),
                WorkerContext.bean(PlatformTransactionManager.class), WorkerContext.bean(JsonMapper.class),
                "http://127.0.0.1:" + webhook.getAddress().getPort() + "/alerts", SECRET, true,
                "127.0.0.1", smtp.getLocalPort(), "alerts", "smtp-password", "sdlc@platform.test",
                List.of("security@platform.test"), true, maxPerHour, 3, "test");
    }

    private void event(String type, String actor, String target) {
        admin.update("""
                INSERT INTO app.platform_security_event (type, severity, actor_ref, target_ref, correlation_id)
                VALUES (?, 'HIGH', ?, ?, 'corr-' || gen_random_uuid())
                """, type, actor, target);
    }

    @Test
    void rulesDeduplicateThresholdAndDeliverContentFreeAlerts() throws Exception {
        SecurityAlerting alerting = alerting(30);
        // A tenant soft deletion alerts once; an identical repeat is deduplicated (count 2, one delivery per channel).
        event("TENANT_SOFT_DELETED", "staff-1", "tenant-x");
        event("TENANT_SOFT_DELETED", "staff-1", "tenant-x");
        // Four MFA failures of one staff member: below the threshold of five. The fifth raises one alert.
        String staff = "staff-" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            event("MFA_FAILED", staff, "org:acme");
        }
        assertThat(alerting.evaluate()).isEqualTo(1);
        event("MFA_FAILED", staff, "org:acme");
        assertThat(alerting.evaluate()).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT event_count FROM app.security_alert WHERE rule = 'TENANT_SOFT_DELETED'",
                Integer.class)).isEqualTo(2);
        assertThat(admin.queryForList("SELECT rule FROM app.security_alert ORDER BY rule", String.class))
                .containsExactly("REPEATED_MFA_FAILURES", "TENANT_SOFT_DELETED");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.security_alert_delivery", Integer.class)).isEqualTo(4);

        assertThat(alerting.deliver()).isEqualTo(4);
        assertThat(bodies).hasSize(2);
        assertThat(mails).hasSize(2);
        // Signed with HMAC-SHA256 over the body; payload is content-free.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertThat(signatures.get(0)).isEqualTo("sha256=" + java.util.HexFormat.of().formatHex(
                mac.doFinal(bodies.get(0).getBytes(StandardCharsets.UTF_8))));
        var payload = new JsonMapper().readTree(bodies.get(0));
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("alertId", "rule", "eventType", "severity",
                "firstEventAt", "lastEventAt", "eventCount", "correlationId", "environment");
        assertThat(String.join("", bodies) + String.join("", mails)).doesNotContain("staff-1").doesNotContain(staff).doesNotContain("org:acme")
                .doesNotContain(SECRET).doesNotContain("smtp-password");
        assertThat(mails.get(0)).contains("Subject: [SDLC security]");
        // Delivered once: a second run sends nothing.
        assertThat(alerting.deliver()).isZero();
    }

    @Test
    void failedDeliveriesRetryBoundedlyAndNeverLoseEvents() {
        webhookStatus.set(500);
        SecurityAlerting alerting = new SecurityAlerting(WorkerContext.bean(JdbcClient.class),
                WorkerContext.bean(PlatformTransactionManager.class), WorkerContext.bean(JsonMapper.class),
                "http://127.0.0.1:" + webhook.getAddress().getPort() + "/alerts", SECRET, true,
                "", 465, "", "", "", List.of(), false, 30, 3, "test");
        String actor = "staff-" + UUID.randomUUID();
        event("PRIVILEGE_CHANGED", actor, "GRANT:RELEASE_APPROVE");
        assertThat(alerting.evaluate()).isEqualTo(1);
        for (int i = 0; i < 3; i++) {
            alerting.deliver();
            admin.update("UPDATE app.security_alert_delivery SET next_attempt_at = now() WHERE status = 'PENDING'");
        }
        assertThat(admin.queryForMap("SELECT status, attempts, last_error_code FROM app.security_alert_delivery"))
                .containsEntry("status", "FAILED").containsEntry("attempts", 3)
                .containsEntry("last_error_code", "WEBHOOK_HTTP_500");
        // The security event is still recorded, independently of delivery.
        assertThat(admin.queryForObject("SELECT count(*) FROM app.platform_security_event WHERE type = 'PRIVILEGE_CHANGED'"
                + " AND actor_ref = ?", Integer.class, actor)).isEqualTo(1);
    }

    @Test
    void alertStormsAreRateLimited() {
        SecurityAlerting alerting = alerting(2);
        for (int i = 0; i < 5; i++) {
            event("PRIVILEGE_CHANGED", "staff-" + i, "GRANT:" + i);
        }
        alerting.evaluate();
        assertThat(admin.queryForObject("SELECT count(*) FROM app.security_alert WHERE rule = 'PRIVILEGE_CHANGED'",
                Integer.class)).isEqualTo(2);
        assertThat(admin.queryForObject("SELECT event_count FROM app.security_alert WHERE rule = 'ALERT_RATE_LIMITED'",
                Integer.class)).isEqualTo(3);
    }
}
