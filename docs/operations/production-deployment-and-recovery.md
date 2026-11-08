# Production Deployment and Recovery (2026-11-08)

This document brings the deployment and recovery procedures together. The detail lives in the linked runbooks.

> **Do not deploy to production.** The gates in `production-readiness-checklist.md` are open, and the owner has not authorised a deployment. The steps below are for a pre-production environment, once those gates allow it.

## 1. Components

| Component | Image / profile | Login | Long-running |
|---|---|---|---|
| Database | PostgreSQL 16 | `sdlc_owner` (objects) | yes |
| Migrations | backend image, `migrate` | `sdlc_migrator` | no (one-shot) |
| API | backend image, default profile | `sdlc_app` | yes |
| Worker | backend image, `worker` | `sdlc_worker` | yes |
| Web | nginx image (SPA plus `/api` proxy) | — | yes |
| Staff provisioning | backend image, `provision` | `sdlc_provisioner` | no (operator only) |
| Retention | backend image, `retention` | `sdlc_retention` | no (daily schedule) |
| Scanner | ClamAV (development and CI); production scanner is O-03 | — | yes |

## 2. Deployment order

1. **Bootstrap the database:**
   - run `db/bootstrap/00-roles.sql` and `01-database.sql`;
   - set the five login passwords from the secret store (`secrets-and-key-management.md`).
2. **Run `migrate`.** It must exit 0. Changesets are forward-only; never edit an applied changeset.
3. **Start the API and worker** with the storage key and MFA key from the secret store:
   - wait for the API `/actuator/health/readiness`;
   - watch the worker liveness file.
4. **Start web.**
5. **Provision staff** with the two-person `provision` commands. Production requires the O-13 ceremony, which is **not implemented**: `bootstrap-admins` is refused outside development/test.
6. **Schedule `retention apply` daily** (`retention-and-deletion.md`). It needs confirmed retention values first.
7. **Smoke check:**
   - sign in;
   - create a requirement;
   - upload a document and watch the scan reach PROCESSED;
   - confirm the staff review queue loads.

Details: `production-deployment.md`; local equivalent: `local-development.md`.

## 3. Rollback

- **Application:** redeploy the previous images.
- **Schema:** changesets 0022 and 0023 add tables, states and grants. The previous application version ignores them, but it cannot read the new states (for example `PAUSED`, `DELETED`, `PURGED`) correctly. A schema rollback is a restore from backup; there are no down-migrations.

## 4. Backup and restore

- Back up the database, the object store and the keys together (`database-backup-and-restore.md`).
- **The procedure has never been exercised.**
- RPO, RTO and backup retention are owner decisions. They are not set here.

## 5. Recovery

| Situation | Runbook |
|---|---|
| Worker stopped or stuck jobs; outbox backlog | `recovery-runbook.md` (lease expiry and fencing make restarts safe) |
| Requirement waiting (documents, clarification, analysis) | `recovery-runbook.md` |
| Requirement FAILED | No retry path (T26 / O-27). Cancel and resubmit |
| Retention run failed | Rerun (idempotent); `retention-and-deletion.md` |
| Security incident | `incident-response.md` |
| Lost staff authenticator | Two-person `reset-mfa` |
| Lost storage or MFA key | **Unrecoverable** for the encrypted data (no KMS yet, SEC-REVIEW-2). Keep offline copies under dual control |

## 6. Verification status of these procedures

| Procedure | Executed here? |
|---|---|
| Migrate on an empty database | Yes, by every integration-test run |
| Retention one-shot from the packaged jar | Yes, a smoke run on 2026-11-08 |
| `docker compose up` | **No**: no Docker daemon |
| Backup and restore | **No** |
| Production deployment | **No**: not authorised and not ready |
