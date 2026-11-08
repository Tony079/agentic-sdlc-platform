# Monitoring and Alerting

## Health
- **API:** `GET /actuator/health/liveness` and `/actuator/health/readiness`. Details are hidden.
- **Worker:** the liveness file `SDLC_WORKER_LIVENESS_FILE` is refreshed every poll. The Compose health check requires it to be younger than 1 minute.
- **Database:** `pg_isready`.

## Logs
Structured ECS JSON (`SDLC_LOG_FORMAT=ecs`). Every request carries a correlation id (header `X-Request-Id`), which is also
stored on audit, security events and model call records. Logs never contain document text, prompts, model output,
tokens, TOTP codes or keys.

Security alerts (C-17) are delivered by the worker to a webhook and/or email: see `security-alerting.md`. The signals below are operational and still need the operator's monitoring stack.

## What to alert on (operational: log patterns and SQL)
| Signal | Source | Suggested threshold |
|---|---|---|
| Dead job | log `ALERT job dead`; `app.job WHERE status = 'DEAD'` | any |
| Dead outbox event | `app.outbox_event WHERE dead_at IS NOT NULL` | any |
| Outbox backlog | `published_at IS NULL AND dead_at IS NULL` older than 5 min | > 0 |
| Malware detected | `app.platform_security_event WHERE type = 'MALWARE_DETECTED'` | any (AC-EXT-33) |
| Probing | `RESOURCE_NOT_FOUND`, `LINK_REQUEST_REJECTED`, `STAFF_ACCESS_DENIED`, `MFA_FAILED`, `LOGIN_THROTTLED` | per actor > 10 per hour |
| Requirements FAILED | `app.workflow_instance WHERE projected_state = 'FAILED'` | any |
| Release blocked | audit `BRD_RELEASE_BLOCKED` | any (TDD 19.5) |
| Analysis waiting on the model | `app.job WHERE job_type = 'BA_ANALYSIS' AND last_error_code = 'MODEL_NOT_CONFIGURED'` | informational until O-01 |
| Model usage and cost | `app.model_call_record` (provider, model, prompt version, tokens, cost, usage source) | per tenant per day |

## Metrics
Micrometer counters exist (for example `sdlc.jobs.dead`). Only the `health` actuator endpoint is exposed; a metrics
backend is not configured.
