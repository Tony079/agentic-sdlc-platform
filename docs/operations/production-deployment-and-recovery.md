# Production Deployment and Recovery (updated 2026-11-08)

This document brings the deployment and recovery procedures together; the detail lives in the linked runbooks.

> **Do not deploy to production.**
> - The gates in `production-readiness-checklist.md` fail or are blocked, and the owner has not authorised a deployment.
> - **Production mode cannot start today:** with `sdlc.environment=production` the API and worker refuse to start with the only KMS that exists (`STORAGE_KMS_LOCAL`).
>
> The steps below are for a pre-production environment.

## 1. Components

| Component | Image / profile | Login | Long-running |
|---|---|---|---|
| Database | PostgreSQL 16 | `sdlc_owner` (objects) | yes |
| Migrations | backend image, `migrate` | `sdlc_migrator` | no (one-shot) |
| API | backend image, default profile | `sdlc_app` | yes |
| Worker (jobs, outbox, security scans, alert delivery, closure records) | backend image, `worker` | `sdlc_worker` | yes |
| Web | nginx image (SPA plus `/api` proxy) | — | yes |
| Staff provisioning and bootstrap ceremony | backend image, `provision` | `sdlc_provisioner` | no (operator only) |
| Retention | backend image, `retention` | `sdlc_retention` | no (daily schedule) |
| Malware scanner | ClamAV (development and CI) | — | yes |

## 2. Deployment order

1. **Bootstrap the database:** run `db/bootstrap/00-roles.sql` and `01-database.sql`, then set the five login passwords from the secret store.
2. **Run `migrate`.** It must exit 0; 104 changesets as of `98a8c8d`. Changesets are forward-only; never edit an applied one.
3. **Configure the API and worker** (`secrets-and-key-management.md`):
   - `SDLC_STORAGE_KEY` and `SDLC_MFA_KEY`;
   - `sdlc.secrets.provider=file` with mounted secrets;
   - at least one alert channel (`security-alerting.md`);
   - extraction off, or on with `sandbox=unshare-net` and `sandbox-required=true` (`extraction-isolation.md`);
   - `SDLC_MODEL_PROVIDER` stays `none` until the owner selects a provider (O-01).
4. **Start the API and worker:**
   - wait for `/actuator/health/readiness`;
   - watch the worker liveness file;
   - in production mode, a refused start lists the failed checks by name.
5. **Start web.**
6. **Provision staff.**
   - Production: the two-person bootstrap ceremony (`release-and-retry-operations.md` §6), then two-person grants for `BUSINESS_REVIEWER`, `BUSINESS_ANALYST`, `RELEASE_APPROVE`, `JOB_RETRY`, `TENANT_DELETE`, `FEATURE_FLAG_ACK` and `REPOSITORY_REGISTER`, plus tenant assignments.
   - Never grant a capability to the person who will check it (separation of duties is also enforced by the database).
7. **Schedule `retention apply` daily** (`retention-and-deletion.md`).
8. **Smoke check:**
   - sign in;
   - create a requirement;
   - upload a document and watch the scan reach PROCESSED;
   - confirm the staff review queue and `/staff/operations` load;
   - trigger a test alert (for example, five wrong TOTP codes for a test staff account) and confirm delivery.

## 3. Rollback

- **Application:** redeploy the previous images.
- **Schema:** changesets 0024–0029 add capabilities, states (tenant DELETED, transitions T04, T05, T26), tables and triggers. The previous application version does not understand them (for example manual release, closure records, soft-deleted tenants). A schema rollback is a restore from backup; there are no down-migrations.

## 4. Backup and restore

- Back up the database, the object store and the keys together (`database-backup-and-restore.md`). SDE2 objects need the key-encryption key that wrapped their data keys.
- **The procedure has never been exercised.** RPO, RTO and backup retention are owner decisions, not set here.

## 5. Recovery

| Situation | Action |
|---|---|
| Worker stopped or stuck jobs | `recovery-runbook.md`. Lease expiry and fencing make restarts safe; stale workers cannot commit |
| Job DEAD | Automatic retries are bounded by count and time (O-27). For a FAILED analysis: operator retry through T26 (`release-and-retry-operations.md` §2). Other job types: fix the cause; there is no generic retry |
| Release blocked | Read the failed gates (`security-scanning.md` §5) |
| Extraction deferring with `EXTRACTION_SANDBOX_UNAVAILABLE` | The host blocks user namespaces (`extraction-isolation.md` §3) |
| Alerts not delivered | `security-alerting.md` §4 |
| Tenant deleted by mistake | Two-person restoration (`retention-and-deletion.md` §4); no data was removed |
| All administrators lost | RECOVERY ceremony (`release-and-retry-operations.md` §6) |
| Lost staff authenticator | Two-person `reset-mfa` |
| Lost storage or MFA key | **Unrecoverable** for the encrypted data until a managed KMS exists. Keep offline copies under dual control |
| Security incident | `incident-response.md` |

## 6. Verification status of these procedures

| Procedure | Executed here? |
|---|---|
| Migrate on an empty database (packaged jar) | **Yes**, 2026-11-08: exit 0, 104 changesets |
| `retention apply` (packaged jar) | **Yes**: exit 0 |
| Ceremony refusal while disabled; production-mode refusal (packaged jar) | **Yes** |
| `docker compose config` | **Yes**, valid with placeholder secrets |
| `docker compose up` | **No**: no Docker daemon |
| Backup and restore | **No** |
| Production deployment | **No**: not authorised and not possible (no managed KMS) |
