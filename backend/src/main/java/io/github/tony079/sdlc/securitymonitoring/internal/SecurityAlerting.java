package io.github.tony079.sdlc.securitymonitoring.internal;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * C-17 security alerting in the worker (owner decision 2026-11-08).
 * <ul>
 *   <li><b>Evaluate:</b> new rows of platform_security_event (cursor) are matched against {@link AlertRules}. An alert
 *       is created once per rule and key (deduplication); repeats only increase its count. At most
 *       {@code sdlc.alerts.max-per-hour} alerts get deliveries; beyond that one ALERT_RATE_LIMITED alert per hour does.</li>
 *   <li><b>Deliver:</b> one delivery per alert and configured channel (unique), retried with exponential backoff up to
 *       {@code sdlc.alerts.max-attempts}, then FAILED. A failed delivery affects nothing else.</li>
 * </ul>
 * Events are persisted by the recorder independently of this class; alerting can be down without losing them.
 */
@Component
@ConditionalOnProperty(prefix = "sdlc.worker", name = "enabled", havingValue = "true")
public class SecurityAlerting {

    private static final Logger log = LoggerFactory.getLogger(SecurityAlerting.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final List<AlertChannel> channels;
    private final int maxPerHour;
    private final int maxAttempts;
    private final String environment;

    SecurityAlerting(JdbcClient jdbc, PlatformTransactionManager transactionManager, JsonMapper json,
                     @Value("${sdlc.alerts.webhook.url:}") String webhookUrl,
                     @Value("${sdlc.alerts.webhook.secret:}") String webhookSecret,
                     @Value("${sdlc.alerts.webhook.allow-loopback-http:false}") boolean webhookLoopback,
                     @Value("${sdlc.alerts.email.host:}") String smtpHost,
                     @Value("${sdlc.alerts.email.port:465}") int smtpPort,
                     @Value("${sdlc.alerts.email.username:}") String smtpUser,
                     @Value("${sdlc.alerts.email.password:}") String smtpPassword,
                     @Value("${sdlc.alerts.email.from:}") String from,
                     @Value("${sdlc.alerts.email.to:}") List<String> to,
                     @Value("${sdlc.alerts.email.allow-loopback-plaintext:false}") boolean smtpLoopback,
                     @Value("${sdlc.alerts.max-per-hour:30}") int maxPerHour,
                     @Value("${sdlc.alerts.max-attempts:6}") int maxAttempts,
                     @Value("${sdlc.alerts.environment:unspecified}") String environment) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.json = json;
        this.channels = List.of(new WebhookAlertChannel(webhookUrl, webhookSecret, webhookLoopback),
                new SmtpAlertChannel(smtpHost, smtpPort, smtpUser, smtpPassword, from, to, smtpLoopback));
        if (maxPerHour < 1 || maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalStateException("sdlc.alerts.max-per-hour >= 1 and max-attempts 1..20 are required");
        }
        this.maxPerHour = maxPerHour;
        this.maxAttempts = maxAttempts;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${sdlc.alerts.interval:30s}", initialDelayString = "10s")
    void scheduled() {
        try {
            evaluate();
            deliver();
        } catch (RuntimeException e) {
            log.error("Security alerting failed", e);
        }
    }

    List<String> configuredChannels() {
        return channels.stream().filter(AlertChannel::configured).map(AlertChannel::name).toList();
    }

    /** Evaluates every new security event; returns the number of alerts created. */
    public int evaluate() {
        int created = 0;
        while (true) {
            Integer batch = tx.execute(s -> evaluateBatch());
            if (batch == null || batch < 0) {
                break;
            }
            created += batch;
        }
        return created;
    }

    private record Event(UUID id, OffsetDateTime at, String type, String actor, String target, String correlation) {
    }

    /** Returns the alerts created, or -1 when there was nothing new. */
    private int evaluateBatch() {
        record Cursor(OffsetDateTime at, UUID id) {
        }
        Cursor cursor = jdbc.sql("SELECT last_occurred_at, last_event_id FROM app.security_alert_cursor FOR UPDATE")
                .query((rs, n) -> new Cursor(rs.getObject(1, OffsetDateTime.class), rs.getObject(2, UUID.class)))
                .single();
        List<Event> events = jdbc.sql("""
                SELECT id, occurred_at, type, actor_ref, target_ref, correlation_id FROM app.platform_security_event
                WHERE (occurred_at, id) > (:at, :id) ORDER BY occurred_at, id LIMIT 500
                """).param("at", cursor.at()).param("id", cursor.id() == null ? new UUID(0, 0) : cursor.id())
                .query((rs, n) -> new Event(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))).list();
        if (events.isEmpty()) {
            return -1;
        }
        int created = 0;
        for (Event event : events) {
            for (AlertRules.Rule rule : AlertRules.RULES) {
                if (rule.eventType().equals(event.type()) && raise(rule, event)) {
                    created++;
                }
            }
        }
        Event last = events.get(events.size() - 1);
        jdbc.sql("UPDATE app.security_alert_cursor SET last_occurred_at = :at, last_event_id = :id")
                .param("at", last.at()).param("id", last.id()).update();
        return created;
    }

    private boolean raise(AlertRules.Rule rule, Event event) {
        String key;
        if (rule.mode() == AlertRules.Mode.SINGLE) {
            key = event.type() + ":" + bucket(event.at(), AlertRules.SINGLE_BUCKET) + ":"
                    + (event.target() == null ? "-" : Integer.toHexString(event.target().hashCode()));
        } else {
            int count = jdbc.sql("""
                    SELECT count(*) FROM app.platform_security_event WHERE type = :type
                      AND occurred_at > :at - make_interval(secs => :window) AND occurred_at <= :at
                      AND (:perActor = false OR actor_ref IS NOT DISTINCT FROM :actor)
                    """).param("type", event.type()).param("at", event.at()).param("window", rule.window().toSeconds())
                    .param("perActor", rule.perActor()).param("actor", event.actor()).query(Integer.class).single();
            if (count < rule.threshold()) {
                return false;
            }
            key = rule.id() + ":" + bucket(event.at(), rule.window()) + ":"
                    + (rule.perActor() && event.actor() != null ? Integer.toHexString(event.actor().hashCode()) : "-");
        }
        int updated = jdbc.sql("""
                UPDATE app.security_alert SET event_count = event_count + 1, last_event_at = greatest(last_event_at, :at)
                WHERE rule = :rule AND dedup_key = :key
                """).param("at", event.at()).param("rule", rule.id()).param("key", key).update();
        if (updated > 0) {
            return false;
        }
        int lastHour = jdbc.sql("""
                SELECT count(*) FROM app.security_alert WHERE created_at > clock_timestamp() - interval '1 hour'
                  AND rule <> 'ALERT_RATE_LIMITED'
                """).query(Integer.class).single();
        boolean limited = lastHour >= maxPerHour;
        String ruleId = limited ? "ALERT_RATE_LIMITED" : rule.id();
        String dedup = limited ? "rate:" + bucket(event.at(), Duration.ofHours(1)) : key;
        UUID alert = jdbc.sql("""
                INSERT INTO app.security_alert (rule, dedup_key, event_type, severity, first_event_at, last_event_at,
                                                event_count, correlation_id)
                VALUES (:rule, :key, :type, :severity, :at, :at, 1, :corr)
                ON CONFLICT (rule, dedup_key) DO UPDATE SET event_count = app.security_alert.event_count + 1
                RETURNING id, (xmax = 0) AS inserted
                """).param("rule", ruleId).param("key", dedup).param("type", event.type())
                .param("severity", limited ? "HIGH" : rule.severity()).param("at", event.at())
                .param("corr", event.correlation())
                .query((rs, n) -> rs.getBoolean(2) ? rs.getObject(1, UUID.class) : new UUID(0, 0)).single();
        if (alert.equals(new UUID(0, 0))) {
            return false;
        }
        for (String channel : configuredChannels()) {
            jdbc.sql("""
                    INSERT INTO app.security_alert_delivery (alert_id, channel) VALUES (:a, :c) ON CONFLICT DO NOTHING
                    """).param("a", alert).param("c", channel).update();
        }
        return true;
    }

    private static long bucket(OffsetDateTime at, Duration size) {
        return at.toEpochSecond() / size.toSeconds();
    }

    /** Delivers due deliveries; returns the number sent. */
    public int deliver() {
        int sent = 0;
        for (int i = 0; i < 100; i++) {
            Integer result = tx.execute(s -> deliverOne());
            if (result == null || result < 0) {
                break;
            }
            sent += result;
        }
        return sent;
    }

    private int deliverOne() {
        record Due(UUID id, String channel, int attempts, String rule, String type, String severity,
                   OffsetDateTime first, OffsetDateTime last, int count, String correlation, UUID alertId) {
        }
        Due due = jdbc.sql("""
                SELECT d.id, d.channel, d.attempts, a.rule, a.event_type, a.severity, a.first_event_at, a.last_event_at,
                       a.event_count, a.correlation_id, a.id
                FROM app.security_alert_delivery d JOIN app.security_alert a ON a.id = d.alert_id
                WHERE d.status = 'PENDING' AND d.next_attempt_at <= clock_timestamp()
                ORDER BY d.next_attempt_at LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """).query((rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class),
                rs.getObject(8, OffsetDateTime.class), rs.getInt(9), rs.getString(10), rs.getObject(11, UUID.class)))
                .optional().orElse(null);
        if (due == null) {
            return -1;
        }
        AlertChannel channel = channels.stream().filter(c -> c.name().equals(due.channel()) && c.configured())
                .findFirst().orElse(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertId", due.alertId().toString());
        payload.put("rule", due.rule());
        payload.put("eventType", due.type());
        payload.put("severity", due.severity());
        payload.put("firstEventAt", due.first().toString());
        payload.put("lastEventAt", due.last().toString());
        payload.put("eventCount", due.count());
        payload.put("correlationId", due.correlation());
        payload.put("environment", environment);
        String code = null;
        if (channel == null) {
            code = "CHANNEL_NOT_CONFIGURED";
        } else {
            try {
                channel.send("[SDLC security] " + due.severity() + " " + due.rule(), json.writeValueAsString(payload));
            } catch (AlertChannel.DeliveryException e) {
                code = e.code().replaceAll("[^A-Z0-9_]", "_");
            }
        }
        if (code == null) {
            jdbc.sql("""
                    UPDATE app.security_alert_delivery SET status = 'SENT', sent_at = clock_timestamp(),
                           attempts = attempts + 1 WHERE id = :id
                    """).param("id", due.id()).update();
            return 1;
        }
        int attempts = due.attempts() + 1;
        boolean exhausted = attempts >= maxAttempts;
        jdbc.sql("""
                UPDATE app.security_alert_delivery SET attempts = :n, last_error_code = :code,
                       status = CASE WHEN :final THEN 'FAILED' ELSE 'PENDING' END,
                       next_attempt_at = clock_timestamp() + make_interval(secs => :delay)
                WHERE id = :id
                """).param("n", attempts).param("code", code).param("final", exhausted)
                .param("delay", Math.min(3600, 30L << Math.min(attempts, 7))).param("id", due.id()).update();
        log.warn("Security alert delivery {} via {} failed ({}), attempt {}{}", due.id(), due.channel(), code, attempts,
                exhausted ? ", giving up" : "");
        return 0;
    }

    /** For tests and the operations view: deliveries by status. */
    List<Map<String, Object>> deliveries(UUID alertId) {
        return new ArrayList<>(jdbc.sql("SELECT channel, status, attempts, last_error_code FROM app.security_alert_delivery "
                + "WHERE alert_id = :a").param("a", alertId).query().listOfRows());
    }
}
