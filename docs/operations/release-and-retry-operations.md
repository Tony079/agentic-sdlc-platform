# Release, Retry and Staff Operations Guide

This guide covers the staff workflows added by the owner decisions of 2026-11-08: O-16, O-27, C-06/C-07, C-09/C-16, O-08 and O-13. Each action needs a staff session with MFA, the named capability (granted only through the two-person provisioning path, S-02) and, for tenant data, an **active assignment to that tenant**. The UI is `/staff/operations`, where sections appear according to the user's capabilities.

## 1. Manual release (O-16)

| Step | Who | API |
|---|---|---|
| 1. Internal approval (unchanged) | `BUSINESS_REVIEWER` | `POST /api/v1/staff/brd-versions/{id}/approve` |
| 2. Release queue | `RELEASE_APPROVE` | `GET /api/v1/staff/releases`, listing APPROVED_INTERNAL versions with content hash and commit SHA |
| 3. Release | `RELEASE_APPROVE`, **not** the internal approver and not a correction author | `POST /api/v1/staff/brd-versions/{id}/release` with `{expectedContentHash, expectedCommitSha, reason}` |

**What happens on release:**
- The API checks the gates and records a `release_approval` bound to the version, content hash and commit SHA. A trigger enforces separation of duties.
- The worker re-checks every gate and only then performs T25 (SHARED_WITH_CLIENT). If a gate fails at that point, the approval becomes INVALIDATED and nothing is shared.
- The gates:
  - the version is current and APPROVED_INTERNAL;
  - the internal approval is VALID;
  - the inputs are unchanged;
  - no clarification is open;
  - the required security scans passed (`security-scanning.md`).

**Errors:**
- 409 `RELEASE_GATE_FAILED` lists the failed gates.
- A content-hash or commit-SHA mismatch means the version changed since you reviewed it. Re-open it.

## 2. Failed jobs and operator retry (O-27)

**Automatic retries:**
- Retries are bounded by attempt count and by a time window per job type (`job_type.max_retry_seconds`): 24 h by default; 6 h for scan, extract and repository validation.
- A job past the window is DEAD with `RETRY_WINDOW_EXCEEDED`.
- Permanent failures are never retried automatically.

**Operator retry:**

| Step | Who | API |
|---|---|---|
| List | `JOB_RETRY`, with an assignment to the active tenant (results are that tenant's jobs only) | `GET /api/v1/staff/failed-jobs`: content-free (job type, code, times) |
| Retry | `JOB_RETRY` and an assignment to the job's tenant | `POST /api/v1/staff/failed-jobs/{id}/retry` with `{reason}` |

**Limits:**
- Only dead **BA analysis** jobs of FAILED requirements can be retried. The retry performs T26 (FAILED → ANALYSING) with a new input set and execution.
- Refused: FATAL failures, `COST_CAP`, invalid proposals, and any other job type (repository, document and scan jobs are not retryable here).
- There is no cross-tenant or generic retry.

Before retrying, read the failure code and fix its cause, for example provider configuration or budget.

## 3. Manual intake (C-06/C-07)

| Action | Who | API |
|---|---|---|
| Queue | `BUSINESS_REVIEWER` | `GET /api/v1/staff/manual-intake` |
| Resume (T04) | `BUSINESS_REVIEWER` | `POST /api/v1/staff/requirements/{id}/resume` with `{expectedRevisionNo, reason}` |
| Close (T05) | `BUSINESS_REVIEWER` | `POST /api/v1/staff/requirements/{id}/close` with `{reason}` |

**Resume:**
- It needs an AUTHORIZED repository link. The owner requests one; the Client Approver authorises it (C-18).
- It creates a new revision (EXISTING_REPOSITORY) and moves the requirement to REPO_VALIDATING. The worker then schedules repository validation and extraction of the stored documents.

**Close:**
- It moves the requirement to CANCELLED, and the owner is notified in-app.
- **There is no reopening.**

Reasons must be 10–500 characters; both actions are audited.

## 4. Change requests (C-09) and the scope diff (C-16)

| Step | Who | API |
|---|---|---|
| Submit | Requirement owner (client), on a SHARED_WITH_CLIENT requirement | `POST /api/v1/requirements/{id}/change-requests` |
| Assess | `BUSINESS_ANALYST` | `POST /api/v1/staff/change-requests/{id}/assessment` |
| Adjust a diff priority (with reason) | `BUSINESS_ANALYST` | `POST /api/v1/staff/change-requests/{id}/diff/{field}/priority` |
| Review | `BUSINESS_REVIEWER`, not the assessor | `POST /api/v1/staff/change-requests/{id}/review` |
| Decide | Client Approver of the tenant, not the submitter | `GET /api/v1/change-requests`; `POST /api/v1/change-requests/{id}/decision` with `{decision: ACCEPT\|DECLINE, note}` |

- Accepting creates a new immutable approved scope version and starts T27 (new analysis).
- The Client Approver sees only the change request (requested change, assessment, prioritised diff), never the full owner-only requirement.
- Diff priorities default by field: security changes or removed acceptance criteria are CRITICAL; objective, acceptance criteria and integrations are HIGH; description, outcomes and constraints are MEDIUM; title is LOW.

## 5. Feature flags (O-08)

| Action | Who | API |
|---|---|---|
| List | Staff with `FEATURE_FLAG_ACK` | `GET /api/v1/staff/feature-flags` |
| Change | Staff with `FEATURE_FLAG_ACK` | `PUT /api/v1/staff/feature-flags/{name}/{environment}` with `{value, reason}` |
| Acknowledge | A **different** operator with `FEATURE_FLAG_ACK` | `POST /api/v1/staff/feature-flag-changes/{id}/acknowledge` |

- Unacknowledged changes appear on `/staff/operations`, and every change raises a LOW alert.
- **Protected flags** (`documents.extraction`, `release.security-scan-gate`) cannot be changed here; they are deployment configuration.

## 6. Production administrator bootstrap and recovery (O-13)

Only for the first administrators of a production environment, or to recover when no administrator remains. Development and test keep `bootstrap-admins`.

**Prerequisites:**
- Both participants exist (`create-staff`) and have enrolled TOTP.
- An operator authorisation (a random value agreed out of band) is prepared. Its SHA-256 is set in `SDLC_PROVISIONING_PRODUCTION_BOOTSTRAP_AUTHORIZATION_SHA256`.
- `SDLC_PROVISIONING_PRODUCTION_BOOTSTRAP_ENABLED=true` is set for this run only.

**Steps:**
1. Open the ceremony. The operator types the authorisation on stdin:

   ```bash
   docker compose run --rm -T provision bootstrap-ceremony-open --purpose=INITIAL --first=EMAIL_A --second=EMAIL_B --ttl-minutes=60
   ```

2. Each participant approves with their own password on stdin and a current code:

   ```bash
   docker compose run --rm -T provision bootstrap-ceremony-approve --ceremony=ID --staff=EMAIL --code=123456
   ```

3. After both approvals the grants are written and the ceremony is COMPLETED. It cannot be reused.
4. Disable the flag again and record the evidence (operator, participants, times, ceremony id).

**Limits:**
- At most 24 hours; one OPEN ceremony at a time; unused ceremonies become EXPIRED.
- For recovery, use `--purpose=RECOVERY` with a **new** authorisation.
