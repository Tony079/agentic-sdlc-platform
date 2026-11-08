# Production Deployment

**Status: not ready for production.** The following gates are open, and each blocks a production go-live.

| Gate | Effect (updated 2026-11-08 after the owner decisions) |
|---|---|
| O-12 / C-13 | Envelope encryption, the secrets provider and the production checks exist, but **no managed KMS adapter**: production mode refuses to start (`STORAGE_KMS_LOCAL`). Needs the hosting platform (O-07) |
| O-01 | Anthropic and OpenAI adapters exist; no provider selected and no benchmark executed. Provider stays `none` |
| O-03 | Secret scanning built in; dependency and SAST products not chosen (they report UNAVAILABLE) |
| O-15 | Sandbox implemented (network namespace, limits, fail closed); residual risk R-02; production host must allow user namespaces |
| O-13 | Implemented: two-person ceremony (`release-and-retry-operations.md` §6) |
| O-25 | Implemented: closure-based retention and two-person soft deletion. Backup expiry (O-07) and log retention are platform settings |
| O-32 | No admin access-grant path (owner decision) |
| GitHub App | Must be created and installed. Real GitHub has not been tested |
| CI | Not run on this branch (not pushed). The Compose runtime was not started in this environment |

See `production-readiness-checklist.md` for the full gate list.

## Procedure (for a pre-production environment once the gates allow it)
1. **Provision PostgreSQL 16:**
   - Run `db/bootstrap/00-roles.sql` and `01-database.sql` as the superuser. `init.sh` does the same in Compose.
   - Set the login passwords: `sdlc_migrator`, `sdlc_app`, `sdlc_worker`, `sdlc_provisioner`, `sdlc_retention`.
2. **Migrate.** Run the image with `--spring.profiles.active=migrate` and `SDLC_DB_MIGRATOR_PASSWORD`. It exits 0 on success. Migrations are forward-only (Liquibase); never edit an applied changeset.
3. **Start the API:**
   - Supply `SDLC_DB_URL`, `SDLC_DB_APP_PASSWORD`, `SDLC_STORAGE_KEY` and `SDLC_MFA_KEY`.
   - Keep `SDLC_COOKIE_SECURE=true` (the default) behind TLS.
   - Readiness check: `/actuator/health/readiness`.
4. **Start the worker:**
   - Profile `worker`.
   - Supply `SDLC_DB_WORKER_PASSWORD` and `SDLC_STORAGE_KEY`, plus the scanner host and port, the GitHub settings (`SDLC_GITHUB_*`) and the model settings (`SDLC_MODEL_*`).
   - Health is shown by the liveness file (`SDLC_WORKER_LIVENESS_FILE`, refreshed every poll).
5. **Web.** The nginx image serves the SPA and proxies `/api`.
6. **Staff.** Run the `provision` process with `SDLC_DB_PROVISIONER_PASSWORD`, only when an operator needs it, and never as a long-running service.
7. **Retention.** Schedule `retention apply` daily, with `SDLC_DB_RETENTION_PASSWORD`, the storage root and the storage key. Only this process holds that password (`retention-and-deletion.md`).

## Rollback
- **Application:** redeploy the previous image. Additive schema changes are compatible with the previous version only if they were designed to be. Check the changeset notes.
- **Database:** no down-migrations exist. Restore from backup (`database-backup-and-restore.md`) to roll back the schema.
