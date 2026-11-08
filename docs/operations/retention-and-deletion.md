# Retention, Closure and Tenant Lifecycle Runbook (O-25)

Decisions: D-R8-03 to D-R8-05 (`docs/decisions/decision-register.md` §14); ADR-05.

> **Changed on 2026-11-08.** The physical `delete-tenant-data` command has been **removed**. Tenant deletion is now a two-person **soft** deletion in the application, and nothing is physically deleted by it. Retention values are the owner-approved O-25 defaults.

## 1. Closure (when the retention clock starts)

- A requirement is **closed** only when the worker writes its `requirement_closure` record. The record is written once, is immutable, and holds `closed_at`, the actor and the terminal state.
- The worker writes it when **all** of these hold:
  - the requirement is CANCELLED or REJECTED;
  - no job is queued or leased;
  - no stage execution is unfinished;
  - no clarification is OPEN, NEEDS_INFO or ANSWERED;
  - no BRD version is IN_REVIEW or APPROVED_INTERNAL (open versions are withdrawn on cancel or reject).
- **SHARED_WITH_CLIENT is not closed.** Release 1 has no completion state, and a released requirement can still change (T27). Its retention clock therefore never starts. A completion state is an open PO decision.
- There is no reopening transition. A closed requirement stays closed.

## 2. Retention process

The `retention` profile is a one-shot process:
- It connects as `sdlc_retention`, the only login allowed to delete derived data and expired audit rows (S-01).
- It works tenant by tenant under RLS, for ACTIVE and SUSPENDED tenants. **DELETED tenants are skipped.**

| Command | Purpose |
|---|---|
| `apply` | Applies the policy. Schedule it **daily** |
| `set-policy --tenant=SLUG --class=SNAPSHOT\|EXTRACTED_TEXT\|DOCUMENT\|BRD\|AUDIT --days=N` | Per-tenant override (1–3650 days), audited |

```bash
docker compose run --rm retention apply
```

Without Compose, run the jar with `--spring.profiles.active=retention` and these settings: `SDLC_DB_URL`, `SDLC_DB_RETENTION_PASSWORD`, `SDLC_STORAGE_ROOT` and `SDLC_STORAGE_KEY`.

The process exits non-zero on any error, including an unknown command. Alert on a non-zero exit and on a missed daily run.

## 3. Policy (O-25 defaults)

| Class | Default days | Variable | Counted from | Deletes |
|---|---|---|---|---|
| SNAPSHOT | 30 | `SDLC_RETENTION_SNAPSHOT_DAYS` | `closed_at` | Encrypted tarball, file, symbol and dependency index rows (snapshot row kept as DELETED) |
| EXTRACTED_TEXT | 30 | `SDLC_RETENTION_EXTRACTED_TEXT_DAYS` | `closed_at` | Extracted text chunks (document kept) |
| DOCUMENT | 365 | `SDLC_RETENTION_DOCUMENT_DAYS` | `closed_at` | Encrypted original (version row kept as DELETED) |
| BRD | 365 | `SDLC_RETENTION_BRD_DAYS` | `closed_at` | Items, evidence, flag acknowledgements; proposal becomes `{"purged": true}` (version kept as PURGED) |
| AUDIT | 365 | `SDLC_RETENTION_AUDIT_DAYS` | The event | Audit rows older than the period |
| Application logs | 90 | Log platform | — | **Not this process.** Configure it on the log platform; not verified here |
| Abandoned workspaces | 24 h | Worker | — | Swept by `DocumentMonitor` (FR-RET-15; stricter than 30 days) |

**Difference from the PRD.** The PRD's development defaults (FR-RET-12) were 90 days for documents and extracted text. O-25 sets document 365 and extracted text 30. Both are configurable.

**Not supported:** legal hold. Retention deletes on schedule, so a legal hold needs a decision and a feature first.

Every deletion writes a content-free audit row.

## 4. Tenant soft deletion

| Step | Who | API |
|---|---|---|
| 1. Request | A Client Approver of the tenant (`POST /api/v1/tenant/deletion-requests` with `{reason}`), or a staff member with `TENANT_DELETE` for that tenant | — |
| 2. Verify | A staff member with `TENANT_DELETE` and an assignment for the tenant, **not** the requester | `POST /api/v1/staff/tenant-lifecycle-requests/{id}/verify` |
| 3. Approve | A **different** staff member with `TENANT_DELETE` (not the requester, not the verifier) | `POST .../{id}/approve` |
| or Reject | Any authorised staff member | `POST .../{id}/reject` with `{note}` |

On approval, in one transaction:
- the tenant becomes **DELETED**;
- client sessions are refused from the next request (`TenantStatusFilter`);
- the worker cancels queued jobs (`DeletedTenantJobCanceller`);
- a `TENANT_SOFT_DELETED` security event is recorded and alerted (CRITICAL).

**No row and no object is removed.** Retention skips the tenant.

The database refuses each of the following:
- a request whose verifier or approver repeats an earlier person;
- any tenant status change outside an executed lifecycle request in the same transaction;
- a status update by the retention login.

### Restoration

`POST /api/v1/staff/tenants/{tenantId}/restoration-requests` with `{reason}` (staff with `TENANT_DELETE`), then verify and approve as above. Because the requester is staff, three different people take part: requester, verifier and approver (tested in `TenantLifecycleIT`). The same holds for a deletion requested by staff. The tenant returns to ACTIVE. Users sign in again; jobs cancelled during deletion are not re-queued automatically.

### Physical purge after soft deletion

**Not implemented and not authorised.** A purge would need its own owner decision (period, legal checks, backups).

## 5. Backups

Copies in backups expire with the backup rotation (FR-RET-06). That is a platform setting (O-07), **not configured**, so AC-EXT-30 is BLOCKED for backups.

## 6. Verification

| Check | Result (2026-11-08) |
|---|---|
| `RetentionIT` (5) | Pass: closure-based deletion by class; audit-row deletion only by `sdlc_retention`; narrow grants; DELETED tenants skipped; no closure record means nothing is touched |
| `TenantLifecycleIT` (2) | Pass |
| `ManualIntakeIT` | Pass: closure recorded once and immutable |
| Jar smoke | `retention apply` exit 0 on a freshly migrated database; `delete-tenant-data` refused as unknown |
| Not verified | Log retention (platform); backup expiry; production scheduling |
