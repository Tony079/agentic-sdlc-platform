# Retention and Deletion (PRD FR-RET, AC-CORE-16, AC-EXT-30, AC-EXT-39)

Decisions: D-R7-09..14 in `docs/decisions/decision-register.md`.

> **The durations below are the PRD development defaults. They are not approved for production.** The Product Owner
> and the Security/Privacy owner must confirm them against contractual and privacy requirements first
> (O-02, O-04, O-23, O-25).

## 1. What runs

The `retention` process is a one-shot Spring profile:
- it serves no HTTP, runs one command, then exits;
- it connects as **`sdlc_retention`**, the only login allowed to delete derived data and expired audit rows (S-01);
- it works tenant by tenant under row-level security.

| Command | Purpose |
|---|---|
| `apply` | Applies the policy to every ACTIVE or SUSPENDED tenant. Schedule it **daily** |
| `set-policy --tenant=SLUG --class=SNAPSHOT\|DOCUMENT\|BRD\|AUDIT --days=N` | Per-tenant override, 1–3650 days; audited as `RETENTION_POLICY_SET` |
| `delete-tenant-data --tenant=SLUG --confirm=SLUG` | Tenant deletion procedure (FR-RET-02) |

Run it locally with Docker Compose. The service uses the `tools` profile and is never started by `up`:

```bash
docker compose run --rm retention apply
```

Without Compose, run the jar with `--spring.profiles.active=retention` and set:
- `SDLC_DB_URL`
- `SDLC_DB_RETENTION_PASSWORD`
- `SDLC_STORAGE_ROOT`
- `SDLC_STORAGE_KEY`

The process exits non-zero on any error. Alert on a non-zero exit, and on a missed daily run.

## 2. Policy

| Class | Default (days) | Counted from | Deletes | Keeps |
|---|---|---|---|---|
| SNAPSHOT | 30 (`SDLC_RETENTION_SNAPSHOT_DAYS`) | Requirement closure | Encrypted tarball; file, symbol and dependency index rows | Snapshot row, status `DELETED` |
| DOCUMENT | 90 (`SDLC_RETENTION_DOCUMENT_DAYS`) | Requirement closure | Encrypted original; extracted text chunks | Version row, state `DELETED`; extraction metadata (no text) |
| BRD | 365 (`SDLC_RETENTION_BRD_DAYS`) | Requirement closure | Items, evidence (with quotes), flag acknowledgements; the model proposal becomes `{"purged": true}` | Version row, status `PURGED`; hashes; approvals |
| AUDIT | 365 (`SDLC_RETENTION_AUDIT_DAYS`) | The event | Audit rows older than the period | Newer rows |

- **Closure** means the requirement is SHARED_WITH_CLIENT, REJECTED or CANCELLED. The closure time is that last transition (D-R7-10).
- **Abandoned workspaces** (24 h) are swept by the worker.
- Every deletion writes an audit row without content: `RETENTION_DELETED` with the resource type and id, or `RETENTION_AUDIT_PURGED`.

## 3. Tenant deletion procedure

1. **Obtain written authorisation**, and confirm the contractual checks. Who may authorise this is an owner decision, not made here (D-R7-13).
2. **Take and verify a backup** (`database-backup-and-restore.md`), if the contract requires a final export.
3. **Run the command:**

   ```bash
   docker compose run --rm retention delete-tenant-data --tenant=SLUG --confirm=SLUG
   ```

   It does the following:
   - the tenant becomes CLOSING (sign-ins stop);
   - all snapshots, documents and BRD content are deleted regardless of age;
   - the tenant becomes CLOSED;
   - `TENANT_DATA_DELETED` is audited.
4. **Audit metadata** remains until its own period ends. The next `apply` runs remove it then.
5. **Backups:** copies in backups expire with the backup rotation (FR-RET-06). That rotation is a platform setting (O-07), which is **not configured**.

## 4. Verification

- `RetentionIT` covers:
  - class-by-class deletion after closure;
  - idempotence;
  - a per-tenant override;
  - removal of content and objects;
  - audit rows;
  - deletion of expired audit rows only by `sdlc_retention` (`sdlc_app` and `sdlc_worker` are refused);
  - the narrow grants;
  - the tenant deletion procedure.
- `SchemaSecurityIT` covers forced RLS on `retention_policy`.
- **Smoke run on 2026-11-08:** the packaged jar with `--spring.profiles.active=retention apply` ran against a migrated database and exited 0.
- **Not verified:**
  - backup expiry;
  - production scheduling;
  - the production object store (O-12).
