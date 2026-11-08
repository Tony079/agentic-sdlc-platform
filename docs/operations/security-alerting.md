# Security Alerting Runbook (C-17)

Decision D-R8-10. Code: `securitymonitoring/internal` (`AlertRules`, `SecurityAlerting`, `WebhookAlertChannel`, `SmtpAlertChannel`).

## 1. What it does

- The **worker** polls `platform_security_event` every `sdlc.alerts.interval` (default 30 s) and applies the rules below.
- **Deduplication.** It creates at most one alert per rule and window (`rule`, `dedup_key`). Further matching events only raise that alert's `event_count`.
- **Rate limit.** Once `sdlc.alerts.max-per-hour` alerts (default 30) were created in the last hour, new events fold into a single `ALERT_RATE_LIMITED` alert (HIGH) per hour bucket. That alert is delivered, so a storm produces one notice instead of silence.
- **Delivery.** Each alert goes to every configured channel, retried with backoff up to `sdlc.alerts.max-attempts` (default 6). Each delivery has its own status: PENDING, SENT or FAILED.
- A failed delivery never loses the event: the alert and its status remain in `security_alert` and `security_alert_delivery`.

**Alerts are for operators only.** User notifications stay in-app (AC-EXT-40).

**Payloads carry no content.** The JSON body holds:
- `alertId`, `rule`, `eventType`, `severity`;
- `firstEventAt`, `lastEventAt`, `eventCount`;
- `correlationId`, `environment`.

There is no tenant name, requirement text, document content, source code or secret. The email subject is `[SDLC security] <severity> <rule>`, and the body is the same JSON.

## 2. Rules

| Rule | Event | Mode | Severity |
|---|---|---|---|
| TENANT_SOFT_DELETED | TENANT_SOFT_DELETED | single | CRITICAL |
| TENANT_RESTORED, TENANT_DELETION_REQUESTED, ADMIN_BOOTSTRAP, PRIVILEGE_CHANGED, MALWARE_DETECTED, GITHUB_CREDENTIAL_FAILURE | same name | single | HIGH |
| RELEASE_GATE_FAILED, LOGIN_THROTTLED | same name | single | MEDIUM |
| FEATURE_FLAG_CHANGED | same name | single | LOW |
| REPEATED_AUTH_FAILURES | LOGIN_FAILED | ≥ 10 in 5 min | HIGH |
| REPEATED_MFA_FAILURES | MFA_FAILED | ≥ 5 in 5 min per actor | HIGH |
| CROSS_TENANT_PROBING | RESOURCE_NOT_FOUND | ≥ 20 in 5 min per actor | HIGH |
| REPEATED_STAFF_DENIALS | STAFF_ACCESS_DENIED | ≥ 5 in 10 min | HIGH |
| LINK_TOKEN_PROBING | LINK_REQUEST_REJECTED | ≥ 5 in 10 min | MEDIUM |

Thresholds are implementer defaults in code (`AlertRules`). Changing them is a code change.

## 3. Configuration (worker)

| Setting | Meaning |
|---|---|
| `SDLC_ALERTS_WEBHOOK_URL` | HTTPS URL of the receiver |
| `SDLC_ALERTS_WEBHOOK_SECRET` | Shared secret. Each request carries `X-Sdlc-Signature: sha256=<hex HMAC-SHA256 of the body>`; the receiver must verify it |
| `SDLC_ALERTS_EMAIL_HOST`, `_PORT` (465), `_USERNAME`, `_PASSWORD`, `_FROM`, `_TO` | SMTP over **implicit TLS**. `_TO` is a comma-separated list |
| `SDLC_ALERTS_ENVIRONMENT` | Label shown in alerts |

- Configure at least one channel. Production mode refuses to start without one (`NO_SECURITY_ALERT_CHANNEL`).
- The loopback flags (`allow-loopback-http`, `allow-loopback-plaintext`) exist for tests only; never set them elsewhere.
- **Secret handling (residual R-04).** The webhook secret and SMTP password are read as Spring properties, not through `SecretsProvider`. In production, supply them from a mounted file (Spring `configtree:` import), not as plain environment variables.

## 4. Operating it

| Situation | Action |
|---|---|
| Delivery FAILED after all attempts | Check the receiver and credentials; the alert stays in `security_alert`. The staff operations page (`/staff/operations`) shows delivery counts; details are in `security_alert_delivery` |
| `ALERT_RATE_LIMITED` received | Treat it as an incident: more than 30 alerts in an hour means an attack or a fault. Review `platform_security_event` directly, because individual alerts are folded |
| A CRITICAL alert (tenant deleted) | Confirm it matches an approved lifecycle request (`tenant_lifecycle_request`) |
| An alert received but not expected | Verify the HMAC signature before acting; an unsigned or wrongly signed message is not from the platform |

## 5. Verification

| Check | Result (2026-11-08) |
|---|---|
| `SecurityAlertingIT` (3) | Pass. Against a loopback webhook and a loopback SMTP listener: deduplication; threshold rule (MFA); content-free payload and signature; bounded retries with recovery; rate limit |
| Real webhook receiver or mail server | **Not verified** |
