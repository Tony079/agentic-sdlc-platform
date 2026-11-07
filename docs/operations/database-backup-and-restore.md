# Database Backup and Restore

**Status: procedure documented, not exercised.** No backup or restore drill has been run in this environment.

## What must be backed up together
1. **The PostgreSQL database `sdlc`.** It holds all metadata, audit, workflow, BRDs and extracted text.
2. **The object store directory (`SDLC_STORAGE_ROOT`, the Compose volume `doc-store`).** It holds the encrypted documents and repository snapshots.
3. **The keys `SDLC_STORAGE_KEY` and `SDLC_MFA_KEY`.** Store them separately from the data backups; without them the backups are unreadable.

## Backup
Take the database dump and the file copy in the same maintenance window, so object keys and rows match.
1. Dump the database:
   ```bash
   pg_dump --format=custom --no-owner --file=sdlc-$(date +%F).dump "$SUPERUSER_URL/sdlc"
   ```
2. Copy the object store (example for Compose):
   ```bash
   docker run --rm -v sdlc-platform_doc-store:/data -v "$PWD":/backup alpine tar czf /backup/doc-store-$(date +%F).tgz -C /data .
   ```

## Restore (to a new, empty server)
1. Run `db/bootstrap/00-roles.sql` and `01-database.sql`, and set the login passwords.
2. Restore the dump:
   ```bash
   pg_restore --no-owner --role=sdlc_owner --dbname="$SUPERUSER_URL/sdlc" sdlc-YYYY-MM-DD.dump
   ```
3. Restore the object store archive into `SDLC_STORAGE_ROOT`.
4. Start `migrate`. It applies only changesets newer than the backup.
5. Start the API and worker with the original keys.
6. Verify:
   - `/actuator/health/readiness` is UP;
   - a known document downloads;
   - `SELECT count(*) FROM app.outbox_event WHERE published_at IS NULL AND dead_at IS NULL` drains.

Row-Level Security and grants are part of the schema and are restored with it. Confirm them with the catalog checks of
`SchemaSecurityIT` (they can be run as SQL).
