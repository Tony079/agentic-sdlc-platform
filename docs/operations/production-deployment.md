# Production Deployment

**Status: not ready for production.** The following gates are open, and each blocks a production go-live.

| Gate | Effect |
|---|---|
| O-13 | The administrator ceremony is not implemented. `bootstrap-admins` is refused outside development/test, so no production staff grants are possible |
| O-12 / C-13 | The production object store and KMS are not decided. Only the encrypted local file store exists, with a single static key |
| O-01 | No model provider is decided. Analysis waits indefinitely with provider `none` |
| O-03 | The production scanner is not decided |
| O-15 | The extractor isolation spike is not accepted. Extraction must stay off for real client documents |
| O-25, O-02, O-04, O-23 | No retention or deletion jobs |
| O-32 | No admin access-grant path |
| GitHub App | Must be created and installed. Real GitHub has not been tested |
| CI | Not run on the R1-M4/M5/M6 branches. The Compose runtime was not started in this environment |

## Procedure (for a pre-production environment once the gates allow it)
1. **Provision PostgreSQL 16:**
   - Run `db/bootstrap/00-roles.sql` and `01-database.sql` as the superuser. `init.sh` does the same in Compose.
   - Set the login passwords: `sdlc_migrator`, `sdlc_app`, `sdlc_worker`, `sdlc_provisioner`.
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

## Rollback
- **Application:** redeploy the previous image. Additive schema changes are compatible with the previous version only if they were designed to be. Check the changeset notes.
- **Database:** no down-migrations exist. Restore from backup (`database-backup-and-restore.md`) to roll back the schema.
