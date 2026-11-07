# Staff Identity, MFA and Provisioning (R1-M4 foundation)

Implements the owner decisions of 2026-11-07 (decision register section 9): O-06, O-11, O-13 and S-02. PRD v0.7 and
TDD v1.0.4 are not modified. This document was written before the code; section 9 lists what is **not** built.

## 1. Data model (global tables, schema `app`)

Staff are global identities, separate from tenant users (`app_user`).

| Table | Purpose | Key constraints |
|---|---|---|
| `staff_user` | Identity: email (unique, case-insensitive), display name, Argon2id password hash, status (ACTIVE / DISABLED), MFA secret (AES-256-GCM ciphertext), `mfa_enrolled_at`, `totp_last_step` (replay guard) | Trigger: an enrolled MFA secret can be cleared only by the provisioning login; `totp_last_step` only increases |
| `staff_capability_grant` | Grants of `STAFF_ADMIN` or `REPOSITORY_REGISTER` to a staff member | CHECK: requester ≠ grantee, approver ≠ grantee, approver ≠ requester. Trigger: requester and approver must hold an active `STAFF_ADMIN` grant (bootstrap rules in section 5). One active grant per (staff, capability) |
| `staff_tenant_assignment` | Explicit assignment of a staff member to one tenant | Same two-person rule; one active assignment per (staff, tenant) |
| `platform_audit_event` | Staff and provisioning audit (no tenant): actor staff id, action, target references, result, correlation id | Append-only for every runtime login |

Capabilities and tenant assignments are independent (O-06): `REPOSITORY_REGISTER` gives no tenant access, and a tenant
assignment gives no capability. `STAFF_ADMIN` lets its holder **request or approve** grants, nothing else. It carries no
tenant access and no repository capability, so no first or bootstrap identity gets "all privileges" (O-13).

## 2. Database role separation (S-02)

| Login | Staff tables |
|---|---|
| `sdlc_app` (API) | SELECT on `staff_user`, grants and assignments. UPDATE only on `staff_user` (`mfa_secret_enc`, `mfa_enrolled_at`, `totp_last_step`), constrained by trigger. INSERT on `platform_audit_event`. **No INSERT, UPDATE or DELETE on grants or assignments; no INSERT into `staff_user`** |
| `sdlc_worker` | None |
| `sdlc_provisioner` (new, LOGIN, NOBYPASSRLS, not an owner) | SELECT, INSERT on `staff_user`, grants, assignments; UPDATE of status/revocation columns and MFA reset; SELECT on `tenant` (id, slug, status); INSERT on `platform_audit_event`. No access to tenant business tables |

`sdlc_provisioner` is used only by the one-shot `provision` process (Spring profile `provision`, no HTTP), started by
an operator. The API, the worker and agents never hold its credentials.

## 3. Authentication flow (API)

1. `POST /api/v1/staff/auth/login {email, password}`: Argon2id check with the same constant-work and throttling rules
   as tenant login. On success the session holds a **pre-MFA** staff principal with the single authority
   `STAFF_MFA_PENDING`. The session id is rotated.
2. If MFA is not enrolled: `POST /api/v1/staff/auth/mfa/enrolment` returns a fresh secret once (base32 and an
   `otpauth://` URI), stored encrypted but not yet enrolled. `POST /api/v1/staff/auth/mfa/enrolment/confirm {code}`
   verifies one code and sets `mfa_enrolled_at`.
3. If enrolled: `POST /api/v1/staff/auth/mfa/verify {code}`.
4. After either step the principal is upgraded to `STAFF` (session id rotated). Nothing staff-protected is reachable
   with `STAFF_MFA_PENDING` only.
5. TOTP: RFC 6238 (HMAC-SHA1, 30 s step, 6 digits, ±1 step window). A used step is rejected afterwards (replay).
   Attempts are throttled per staff account. Codes and secrets never appear in logs, audit or responses (except the
   one-time enrolment response).

Staff never reach the client requirement or document APIs; those stay limited to `CLIENT_USER` / `CLIENT_APPROVER`.

## 4. Active tenant (O-06)

`PUT /api/v1/staff/session/active-tenant {tenantId}` succeeds only if an active assignment exists. **Every staff
request re-checks** that the staff member is still ACTIVE, the assignment is still active, and (for capability-protected
operations) the capability grant is still active. A revocation takes effect on the next request. The tenant context is
taken from the active tenant, so forced RLS applies exactly as for tenant users. A tenant id in a request body is never
trusted, except as the *target* of the active-tenant call, which is validated against assignments.

## 5. Provisioning and bootstrap (O-13)

All grants go through the `provision` process (`sdlc_provisioner`). The operator supplies the identities; **each
approving or requesting staff member proves presence with a current TOTP code**, verified by the process (replay-guarded).
This gives a two-person control even though an operator runs the command.

| Command | Rule |
|---|---|
| `create-staff` | Creates an ACTIVE staff identity without capabilities or assignments; password read from stdin, never from arguments or environment |
| `bootstrap-admins` | **Development/test only** (`sdlc.provisioning.environment` = `development` or `test`; refused otherwise). Allowed only while no active `STAFF_ADMIN` grant exists. Grants `STAFF_ADMIN` to A (approved by B with B's TOTP) and to B (approved by A with A's TOTP), both marked `bootstrap` with no requester. The trigger allows at most two bootstrap grants, only while every active admin grant is a bootstrap grant |
| `grant-capability`, `assign-tenant` | Requester and approver: two distinct `STAFF_ADMIN` holders, both MFA-enrolled and presenting TOTP; neither may be the grantee |
| `revoke-capability`, `revoke-assignment`, `disable-staff` | One `STAFF_ADMIN` with TOTP (lowering privilege only) |
| `reset-mfa` | Two distinct `STAFF_ADMIN` holders with TOTP, neither being the target |

**Production:** the two-step administrator ceremony is **not implemented**. In any environment other than development or
test, `bootstrap-admins` is refused, so no administrator, and therefore no grant, can exist in production until the
ceremony is built and approved.

## 6. Audit events (platform_audit_event, content-free)

- **Authentication:** `STAFF_LOGIN` (success or denied), `STAFF_MFA_ENROLLED`, `STAFF_MFA_VERIFIED`, `STAFF_MFA_FAILED`, `STAFF_ACTIVE_TENANT_SET` (success or denied).
- **Grants and assignments:** `STAFF_CAPABILITY_GRANTED` / `_REVOKED`, `STAFF_TENANT_ASSIGNED` / `_REVOKED`.
- **Staff lifecycle:** `STAFF_CREATED`, `STAFF_DISABLED`, `STAFF_MFA_RESET`, `STAFF_BOOTSTRAP`.

Staff actions inside a tenant (repository registration) are also written to the tenant `audit_event` with actor type
`STAFF`.

## 7. Failure handling

- Wrong password or unknown email: same response and timing as tenant login.
- Wrong or replayed TOTP: 401 `MFA_FAILED`, plus throttling.
- Revoked assignment or capability: 403 on the next request; the active tenant is cleared.
- Disabled staff: the session is invalidated on the next request.

## 8. Configuration

| Variable | Used by | Purpose |
|---|---|---|
| `SDLC_MFA_KEY` | api, provision | 32 random bytes, base64. Encrypts TOTP secrets |
| `SDLC_DB_PROVISIONER_PASSWORD` | `provision` profile only | Provisioning login password |

## 9. Not built in this branch

- The production two-step administrator ceremony.
- A staff self-service UI for grants.
- WebAuthn.
- MFA recovery codes. Recovery is the two-person `reset-mfa` command, which keeps the "no MFA bypass" rule.
