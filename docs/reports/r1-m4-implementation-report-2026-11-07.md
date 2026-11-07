# R1-M4 Implementation Report: Repository Registration and Integration (2026-11-07)

Branch `feature/r1-m4-repository-registration` (local only). Scope: the owner decisions of 2026-11-07 (decision
register section 9) and the implementation decisions D-M4R-01..16 (section 10). PRD v0.7 and TDD v1.0.4 are not
modified.

## 1. Git

| Item | Value |
|---|---|
| Starting branch and SHA | `claude/model-gateway` at `e3feb84` (PR #6 head), clean tree |
| New branch | `feature/r1-m4-repository-registration`, based on `e3feb84`, no upstream (never pushed) |
| Commits | `b62b7e3` decisions and designs · `c51c517` staff identity · `b44fa51` C-18 · `791a864` registration, broker, validation, snapshot, inventory · `6e16ada` security cleanup · `bdbd290` frontend and docs (+ this report) |
| Diff against `e3feb84` | 104 files changed before this report (57 added, 47 modified) |
| PR branches #1–#6 | Unchanged; local equals remote: `652fa9a`, `1bc15f7`, `bf4318f`, `ab1ac05`, `7e98a34`, `e3feb84` |
| Push, merge, PR, rebase, force-push | None |

## 2. What was built

### 2.1 Staff identity, MFA and provisioning (O-06, O-11, O-13, S-02)

- **Changeset 0017.** It adds:
  - the tables `staff_user`, `staff_capability_grant` (`STAFF_ADMIN`, `REPOSITORY_REGISTER`), `staff_tenant_assignment` and `platform_audit_event`;
  - invoker-rights guard triggers, which enforce:
    - two distinct administrators per grant or assignment;
    - a development/test-only bootstrap of at most two administrators;
    - credential and status changes only through the provisioning login;
    - an MFA enrolment window and a forward-only TOTP replay step.
- **New login `sdlc_provisioner`.** It is used only by the one-shot `provision` profile. `sdlc_app` can read staff data and write only the MFA columns. `sdlc_worker` has no access.
- **Staff API:**
  - login, then a pre-MFA principal, then TOTP enrolment or verification (RFC 6238, ±1 step, replay-guarded, throttled);
  - an active tenant gated by assignment;
  - every staff request is re-checked against the database: a disabled member's session is invalidated, and a revoked assignment clears the active tenant;
  - staff cannot reach the client APIs.
- **`StaffAccess`.** A per-call database check of status, assignment and capability, with a security event on refusal.
- **Provisioning command line:**
  - `create-staff` (password from stdin);
  - `bootstrap-admins` (development/test only);
  - `grant-capability` and `assign-tenant` (two administrators, each with a TOTP code);
  - `revoke-*`, `disable-staff` and `reset-mfa`.

  `bootstrap-admins` is refused in any other environment. The production two-step ceremony is **not implemented** (D-M4R-03).

### 2.2 C-18 one-time link request and Q-A authorisation

- **Changeset 0018.** Adds `repository_link_request` (stores the token's SHA-256 only, with expiry 1–168 h and one pending request per requirement) and `requirement_repository_link` (AUTHORIZED / REGISTERED / SUPERSEDED). Both have forced RLS, column grants, and triggers that re-check the Client Approver role.
- **Owner.** Issues, revokes and views requests.
- **Client Approver.** Redeems a request with a GitHub URL; the response is `{linkId, status}` only.
- **Failures.** Every token failure returns the same 404 plus a `LINK_REQUEST_REJECTED` security event. The approver still has no visibility of the requirement.

### 2.3 Registration (C-01)

- `POST /api/v1/projects/{id}/repositories {linkId}`. Requires staff with MFA, an active assignment and `REPOSITORY_REGISTER`.
- The repository identity comes only from the AUTHORIZED link.
- The tenant audit records actor type `STAFF`.
- Publishes outbox `RepositoryRegistered` (D-M4R-10).
- The API holds no GitHub credential.

### 2.4 GitHub broker (worker only; disabled by default)

- **App JWT.** RS256, at most 9 minutes.
- **Installation token.** Narrowed to one repository with `contents:read` and `metadata:read`. A token with wider grants is refused (D-M4R-14). The token is kept in memory for one job only.
- **HTTP rules:**
  - HTTPS on allowed hosts only;
  - at most one tarball redirect, only to an allowed host, and the token is not forwarded;
  - JSON responses are capped at 1 MB;
  - rate limits cause deferral.
- **Private key.** PEM file (PKCS#8 or PKCS#1), referenced by path only.

### 2.5 Verification, validation and workflow (Q-B Option A)

- **REPO_VERIFY.** The outcome is one of:
  - VERIFIED, with GitHub's repository id and default branch;
  - ACCESS_DENIED (App not installed, 403/404);
  - UNAVAILABLE (moved, identity changed, or the repository is registered by another tenant; generic, D-M4R-13).
- **REPO_VALIDATE:**
  - defers without using retry budget until the repository is registered and verified (D-M4R-11);
  - pins the default-branch head SHA;
  - then performs **T06**, or **T07** when an attachment needs a decision.
  - It never performs T08, never moves to ANALYSING and never enqueues a BA job.
- **T09.** A dead REPO_VALIDATE job moves the requirement to FAILED. There is no T30 and no administrative retry.
- **T29.** Owner cancellation is added from AWAITING_DOCUMENTS, AWAITING_DOCUMENT_DECISION and FAILED.

### 2.6 Snapshot and inventory

- **REPO_SNAPSHOT:**
  - stores the tarball encrypted through `ObjectStore` (at most 200 MB);
  - checks the pax global header SHA;
  - enforces at most 50,000 entries, 1 GB expanded and a ratio of 100;
  - rejects unsafe paths and skips links and special files;
  - never extracts to disk;
  - deterministic violations are recorded as a FAILED snapshot (D-M4R-16).
- **REPO_INDEX:**
  - JavaParser on files of at most 1 MB with a 10 s time limit: types, methods, Spring stereotypes, request mappings;
  - Maven via hardened StAX (no DTD, no external entities);
  - Gradle via patterns.

### 2.7 Frontend

- **Owner.** A repository panel: one-time approver code, link status, validation and snapshot status.
- **Approver.** A redeem page.
- **Staff.** Sign-in with TOTP enrolment or verification, an active-organisation selector, and registration of authorised links.
- **Requirement page.** Cancel is offered from the new states.

## 3. Test results (executed 2026-11-07, PostgreSQL 16, this branch)

| Suite | Result |
|---|---|
| Backend `mvn verify` | **232 tests, 0 failures, 0 errors, 0 skipped**: 68 unit and 164 integration, in 37 classes. Previous baseline: 178 |
| New backend tests | `TotpTest` 4 · `MfaCipherTest` 2 · `StaffProvisioningIT` 11 · `StaffAuthIT` 7 · `LinkRequestIT` 7 · `GitHubBrokerTest` 7 · `SnapshotArchiveTest` 5 · `IndexerTest` 4 · `RepositoryIntegrationIT` 7 |
| Frontend | Vitest **30/30** (22 existing + 8 new); `tsc --noEmit` OK; ESLint OK; `vite build` OK |
| Secret scan | gitleaks 8.21.2 `detect` over full history (37 commits): no leaks. Three reviewed false positives (PEM header literals in `791a864`, no key material) are listed by exact fingerprint in `.gitleaksignore`; the literals were removed in `6e16ada` |
| Compose | `docker compose config` valid, including the `tools` profile; a missing `SDLC_MFA_KEY` is refused. **The stack was not started:** no Docker daemon in this environment |
| Real GitHub | **Not tested.** All GitHub behaviour was tested against the in-process fake only |

## 4. Security review (self-review; not an independent review)

| ID | Finding | Status |
|---|---|---|
| SR-M4-1 | JavaParser runs inside the worker JVM; a timed-out parse is abandoned, not killed | Residual risk (D-M4R-07) |
| SR-M4-2 | Provisioning TOTP codes appear in command arguments | Known limit, development/test (D-M4R-09) |
| SR-M4-3 | Login and MFA throttles are in memory, per instance | Existing limit D-M1-02 |
| SR-M4-4 | `SDLC_MFA_KEY` is a single static key from the environment, with no rotation | Same class as SEC-REVIEW-2 (O-12/C-13) |
| SR-M4-5 | A submitted requirement can wait indefinitely in REPO_VALIDATING until staff register it | D-M4R-11; Product Owner point |
| SR-M4-6 | Snapshots are kept indefinitely | O-25 open |
| SR-M4-7 | gitleaks false positives in history | Resolved by `.gitleaksignore` (exact fingerprints) |
| SR-M4-8 | Disabled-staff session handling recursed through Spring Session until the connection pool ran out (found by a test) | Fixed: the filter answers 401 directly |

## 5. Changes outside the new modules (necessary, recorded)

- `@Profile("!migrate & !provision")` on seven existing beans (document, extraction, storage, workflow verifier, model gateway), so that the provisioning process starts with its narrow login (D-M4R-15). There is no behaviour change for the API or the worker.
- The audit actor type `STAFF`, `TenantContext.currentActorType()`, the `JobQueue.makeAvailable` method, `DocumentProcessing.needsDecision`, `RequirementDirectory.projectOf`, and engine methods for T06/T07/T09.
- The model gateway logic and the extraction gate (O-15) are unchanged.

## 6. Outstanding gates

| Gate | Effect |
|---|---|
| GitHub App (external prerequisite) | Integration off by default; real verification needs an App, a test organisation and repository, and a secret store |
| O-13 production ceremony | Not implemented; production bootstrap refused, so no production staff grants are possible |
| O-25 snapshot retention | No deletion; production retention blocked |
| O-12 / C-13 production storage and keys | Development/test encrypted store only |
| O-27 / S-06 retry after repository failure | No retry path, no T30 |
| O-15 extraction isolation | Unchanged (gate default off) |
| O-01 model provider | Unchanged |
| D-M4R-04, D-M4R-10, D-M4R-11, D-M4R-16 | Flagged for Product Owner or Architecture Owner review |
| CI | Not run for this branch (not pushed) |
