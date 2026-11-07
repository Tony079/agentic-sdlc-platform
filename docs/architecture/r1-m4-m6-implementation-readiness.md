# Implementation Readiness — PRD R1-M4, R1-M5, R1-M6

Status (2026-11-05): **preparation only; none of these milestones is started.** This document turns PRD v0.7 and
TDD v1.0.4 into buildable work packages and names, for each part, the owner decision it waits for.

- It does not change the PRD or the TDD; it cites them by section.
- It resolves no decision. Where a choice is still open it lists the options; it does not pick one.
- Decision IDs refer to `docs/decisions/decision-register.md`.

## 0. Cross-milestone foundations already built (re-use, do not rebuild)

| Need | Existing component | Where |
|---|---|---|
| Async work with leases, fencing, retries, dead-letter | Job queue (`queue` module): `JobQueue`, `JobHandler` (execute outside tx, fenced `commit`) | M3, PR #3 |
| Events and idempotent consumers | Transactional outbox (`outbox` module), `EventConsumer` | M3, PR #3 |
| Engine-only state changes | `WorkflowEngine` (+ DB transition CHECK, definition R1-v1 check at start-up) | M2/M3 |
| Stage executions | `stage_execution` table (worker-written) | M3 |
| Private storage | `ObjectStore` (encrypted, per-tenant keys) | M4, PR #4 |
| Document text with positions | `document_chunk` (page or heading label, full-text index) | M5, PR #5 |
| Model access | `ModelGateway` + `FakeModelProvider`, `model_registry`, `prompt_version` (immutable), `model_call_record` (with `usage_source`) | PR #6 |
| Worker login / grants pattern | `sdlc_worker`, column-level grants, forced RLS | M3 onwards |

Every new transition below requires three things in the **same reviewed change**:
- extending the `workflow_transition` CHECK constraints;
- updating the stored definition R1-v1, or publishing R1-v2;
- updating `TransitionTable`.

The engine refuses to start if these disagree.

---

## 1. R1-M4 — Repository registration, snapshot and Phase A inventory

> **Update 2026-11-07.** The Product Owner decided C-01/O-05 (internal staff with the `REPOSITORY_REGISTER`
> capability), C-18 (Option 1 link request, the `requirement.repository_link.attach` capability), Q-A (Option B),
> Q-B (Option A: T06/T07 only) and the staff model O-06, O-11, O-13, S-02 (decision register section 9).
> Implementation: `feature/r1-m4-repository-registration`. Designs: `staff-identity-and-provisioning.md` and
> `repository-integration.md`. Still open: O-25, O-27/S-06, O-12/C-13 and the GitHub App itself (external). The option
> tables below are kept for history.

PRD: AC-CORE-02, FR-REPO-01..08 (FR-REPO-05: never executed or built), J2. TDD: 14.1–14.9, 9.3 T02/T06–T09, 10.10 (O-27).

### 1.1 Blocking decisions

| ID | Question | Why it blocks |
|---|---|---|
| **C-01 / O-05** | Who registers a repository? The PRD says "an admin" (J2, AC-CORE-02); TDD 14.3 provisionally says the Client Approver; TDD 4.1 says Platform Admin | It decides the API's authorisation rule and whether R1-M4 needs staff accounts (and therefore O-06/O-13) |
| **C-18** | May a Client Approver see or act on a Client User's requirement? | M2-C06 has the Client Approver attach the repository link. Owner-only visibility (current) forbids it |
| **O-06 / O-13** | Staff accounts and provisioning | Only needed if C-01 resolves to "Platform Admin" |
| O-25 | Snapshot retention | Snapshot retention period (TDD default: life of the requirement) |
| O-27 / S-06 | Retry from REPO_VALIDATING failure | PRD has no such transition; stays disabled (S-27) |
| — | **GitHub App** (owner action, not a decision) | A platform-owned App with Contents: Read and Metadata: Read; App id, private key in the secret store, installation by the client organisation owner |

### 1.2 Options for C-01 (owner to choose; no recommendation is implied by order)

| Option | Effect |
|---|---|
| A. Client Approver registers | Matches TDD 14.3 and M2-C06; still needs C-18 if the requirement belongs to a Client User |
| B. Platform Admin registers | Matches PRD J2/AC-CORE-02 wording; requires O-06 and O-13 first (no staff accounts exist) |
| C. The requirement owner registers, and a Client Approver confirms | A new rule; needs a PRD clarification |

### 1.3 Work package (once unblocked)

**Data** (new changesets, forced RLS, worker-only writes unless stated):
- `github_installation`: tenant, installation id, account login, status.
- `repository`: tenant, `github_repo_id` UNIQUE across tenants, with a generic conflict response; owner/name; default branch; status.
- `project_repository`: association, tenant-composite FKs (PRD §2A).
- `requirement_repository`: requirement → repository link.
- `repository_snapshot`: repository, **commit SHA**, opaque storage key, file count, bytes, indexer version, status.
- `repository_file`: path, language, size, hash, supported flag.
- `code_symbol` (Java): package, class, method, Spring stereotype, path and line range.

**Jobs:**
- `REPO_VALIDATE` (already queued by T02).
  - The broker mints a 1-hour installation token scoped to one repository.
  - It verifies the installation covers the repository and that the repository is not registered by another tenant.
  - It resolves the default branch and its head SHA, and pins it.
  - Outcomes: T06/T07/T08 per guard 9.4; 404/403 → FATAL `REPO_ACCESS` → T09; 429 → deferral; 5xx → retryable.
- `REPO_SNAPSHOT`.
  - Tarball for the pinned SHA from `api.github.com` / `codeload.github.com` only; no cross-host redirects (SSRF).
  - Trusted extraction with limits: 200 MB, 50,000 files, 1 MB per indexed file, ratio limit; no `..` or absolute paths; symlinks and special files skipped.
  - The content is stored encrypted via `ObjectStore`.
- `REPO_INDEX`: file inventory; Java symbols with a bounded parser and a per-file time limit; `pom.xml` / `build.gradle` read as text. **Nothing compiled or executed.**

**Security:**
- The private key is held only by the broker, which lives in the worker; it is never logged or passed to agents.
- An egress allow-list for GitHub hosts.
- Repository content is untrusted data.
- Analysis is static only.

**API:**
- `POST /api/v1/projects/{id}/repositories`: register by owner/name; authorisation per C-01.
- `GET` repository status.
- `POST /api/v1/requirements/{id}/repository`: link; authorisation per M2-C06/C-18.
- Errors: `409 REPOSITORY_UNAVAILABLE` (generic; reveals nothing about other tenants), `422 INVALID_REPOSITORY`.

**Audit:** `REPOSITORY_REGISTERED`, `REPOSITORY_VALIDATED`, `REPOSITORY_ACCESS_DENIED`, `SNAPSHOT_CREATED` (ids, SHA, counts; no content).

**Frontend:** repository registration and status on the project page; the pinned SHA shown on the requirement.

**Tests:**
- A fake GitHub HTTP server: install/token/repository/tarball/rate-limit/404.
- A cross-tenant registration conflict.
- A token scoped to one repository; no token in logs.
- Hostile tarballs: traversal, symlink, bomb, oversized.
- SHA pinning; T06–T09 paths.
- No execution: an ArchUnit/static check that the repository module never starts processes.
- A REPO_VALIDATE retry stays impossible after T09 (S-06/S-27).

### 1.4 Entry and exit criteria

- **Entry:**
  - C-01/O-05 decided.
  - C-18 decided, if the decision needs approver access.
  - O-06/O-13, if C-01 = Platform Admin.
  - A GitHub App created, its credentials in the secret store for CI/staging; a test organisation with a sample repository.
- **Exit:**
  - AC-CORE-02 evidence: registration, read-only permissions, SHA pinned.
  - REPO_VALIDATE / SNAPSHOT / INDEX green against the fake GitHub server in CI, and once against the real test organisation.
  - No-execution and egress checks pass.
  - Gate-G1 items for repositories documented.

---

## 2. R1-M5 — Agent runtime, BA agent, BRD with traceability and evidence checker

PRD: §11, FR-BA-*, FR-LLM-01..11, FR-DOC-*, AC-CORE-03/04/05/12/13, AC-DOC-10..14, AC-EXT-26/27/41/43. TDD: 12, 13, 16, 9.4, 9.6, T08/T11/T13–T19.

### 2.1 Blocking decisions and dependencies

| ID | Effect |
|---|---|
| **R1-M4** | The workflow reaches ANALYSING only through T08/T11/T13 after repository validation; the BA input needs a pinned snapshot |
| **O-01** | The real model provider. Until decided only `FakeModelProvider` exists; `sdlc.model.provider` defaults to `none` (no data leaves the platform). The provider evaluation follows FR-LLM-08/10/11 |
| **O-15** | Real client documents cannot be extracted; the BA agent would only see synthetic documents |
| O-08 | Flagged-fact acknowledgement |
| O-09 | Budget-cap values (the mechanism exists in the gateway) |
| O-10 | Re-run policy on document change |
| O-33 | Evaluation corpus |

### 2.2 Work package

**Agent runtime** (module `agentruntime`):
- A tool registry of read-only tools bound to the job's tenant and snapshot: `list_files`, `read_file_range`, `search_symbols`, `find_references`, `read_document_chunk`, `search_document_chunks`.
- Byte caps: 64 KB per repository range, 16 KB per document chunk; at most 40 tool calls per phase.
- Tool results are wrapped as `DataBlock`s and never merged into instructions.
- **Gap to close here:** the gateway has no tool-calling API yet (FR-LLM-01 asks for chat, structured output and tool calling). Tool calling should be added to `ModelProvider.Call` / `ModelGateway` together with the registry. The registry, not the model, authorises every tool name.

**Input set:**
- The frozen list covers the requirement revision, repository SHA, PROCESSED document versions and chunk set, answered clarifications and the prompt version.
- It is hashed with SHA-256 into `stage_execution.input_hash`.
- Staleness (9.6): a document or revision change raises `input_generation`, marks executions STALE, cancels jobs (epoch raised) and makes pending results unapprovable.

**BA_ANALYSIS job:**
- Phase A (inventory) and Phase B (impact), each a versioned prompt in `prompt_version`, called through `ModelGateway` with a strict output record.
- The output record has items with kind FACT / ASSUMPTION / RECOMMENDATION / QUESTION, statement, evidence[], risk, component; plus questions[], unsupported[] and excluded_documents[].
- **Tx P** (pending result): `assertLease`, then `ba_result_pending` (proposal, input hash, epoch, model, prompt), then finish, then outbox → BA_COMMIT_RESULT.

**BA_COMMIT_RESULT job (Tx C, trusted engine):**
- Checks: lease; stage identity; not STALE or CANCELLED; state ANALYSING; **input hash equals the current input set**; schema re-validated.
- Citation verification (16.3) by code:
  - repository evidence: SHA, path, line range, range hash;
  - document evidence: version in the input set, chunk, label, quoted text present;
  - a lexical support check.
- A failing FACT becomes ASSUMPTION or FLAGGED.
- On success: create `brd`, `brd_version`, items, evidence and flags, then T18 and T20, then `BrdVersionCreated`. Otherwise reject (REJECTED_STALE / REJECTED_INVALID) and create no BRD.

**Clarifications from the BA:** QUESTION items become clarifications (TDD 17). A blocking clarification pauses only the dependent work (T16/T17).

**Failure paths:** BA job DEAD → stage FAILED → T19 (FAILED). Cost cap → FATAL `COST_CAP`.

**Tests:**
- EV-01..08 (forged, cross-version and cross-tenant citations).
- WF-S02 (document deleted during analysis → Tx C rejects).
- Lease and epoch at Tx C.
- SEC-PI-01..05 prompt-injection corpus (hostile repository files and documents).
- AGT-CAP-01 (cost cap).
- A provider-replacement test: a second fake adapter with no agent code change (FR-LLM-09).

### 2.3 Entry and exit criteria

- **Entry:**
  - R1-M4 exit met.
  - O-01 decided, or an explicit owner instruction to proceed with the fake provider only, in which case real BRD quality is not assessed.
  - O-15 closed before any real client document.
  - O-08/O-09/O-10 provisional values confirmed.
- **Exit:**
  - AC-CORE-03/04/05/12/13 and AC-EXT-26/27/41/43 evidence on fixtures.
  - Injection corpus green in CI.
  - No approvable BRD from stale or invalid inputs.
  - Every model call recorded (FR-LLM-04).

---

## 3. R1-M6 — Review, clarifications, versioning and staleness, client view, notifications, Gate G1

PRD: FR-DOC-*, FR-CLR-*, FR-AUTH-16/22, J5–J7, AC-CORE-06/07/08/11/14, AC-DOC-15/19/20, AC-EXT-29/37/40, §12 Gate G1. TDD: 17, 19, 20.8, 20.9, T20–T27.

### 3.1 Blocking decisions

| ID | Effect |
|---|---|
| **O-06** | The Business Reviewer is a staff role. There are no staff accounts, no assignment model and no active-tenant session. **No temporary role or bypass will be created** |
| **O-11** | Staff MFA, required before staff can sign in |
| **O-13** | Staff and tenant provisioning, two-admin bootstrap |
| O-32 | Admin access grants to tenant content (AC-EXT-37) |
| C-05 | Reviewer-raised clarification endpoint |
| C-07 | Admin cancellation in MANUAL_INTAKE |
| C-09 | Client comments; which client role requests changes |
| C-16 / O-22 | Version diff priority |
| O-16, O-21 | Milestone-level product details |
| S-02 | Role grants must not be writable by the runtime login (design required before staff roles exist) |

### 3.2 Work package (once unblocked)

**Staff identity** (per the accepted O-06/O-11/O-13 text): `platform_user`, `platform_role_assignment` granted under four-eyes **through a privileged path the runtime login cannot write** (S-02), `reviewer_assignment`, and an active-tenant session.

**Review:**
- `brd_version` lifecycle DRAFT → IN_REVIEW → APPROVED, REVISION_REQUESTED or REJECTED (T20–T23).
- Reviewer corrections create a new version (16.6) and keep revision history.
- A version diff, if O-22 makes it P0.

**Approval binding (19.2):**
- An immutable approval row (type BRD_REVIEW, approver, `brd_version_id`, `content_hash`, `input_set_hash`, time).
- Database checks: approver ≠ requirement submitter (FR-AUTH-16, AC-DOC-20) and approver ≠ author of any edit of that version.
- Concurrent approvals: one winner via optimistic version (AC-CORE-11).
- Invalidation (19.4): any input change marks the version STALE and the approval invalid.
- Release T25 (separate transaction) only while the approval is still valid.
- **Client approval never counts as an internal approval or a release approval** (approval types are distinct, FR-DOC-09).

**Clarifications (TDD 17):**
- A state machine with blocking and non-blocking types and an assignee (client user or internal analyst).
- Answer validation and a stale-input check.
- Reassignment and escalation.

**Notifications:** in-app only (FR-CLR-04/12), with an inbox and a badge.

**Client view:**
- SHARED_WITH_CLIENT shows the released version only.
- Citations show paths, names and commits with no source excerpts (AC-EXT-29).
- T27 client change request (role per C-09).

**API (TDD 20.8/20.9):** review queue, BRD version read, edit, approve, request revision, reject, release, client view, clarification create/answer/withdraw, notifications.

**Security tests:**
- Self-approval refused, in the API and the database.
- A stale-version approval refused (409 STALE_INPUTS).
- An approval for another tenant refused.
- No client view before release (AC-CORE-08).
- Admin access is time-limited and audited (O-32).
- The Gate G1 checklist (PRD §12).

### 3.3 Entry and exit criteria

- **Entry:**
  - O-06, O-11, O-13 accepted, with the S-02 design.
  - R1-M5 exit met.
  - C-05, C-07, C-09, C-16 decided, or explicitly deferred by the Product Owner.
- **Exit:**
  - AC-CORE-06/07/08/11/14, AC-DOC-15/19/20 and AC-EXT-29/40 evidence.
  - AC-EXT-37 requires O-32.
  - The Gate G1 review passes.

---

## 4. Dependency map

```
O-05/C-01 ─┬─> R1-M4 (repository) ──> R1-M5 (BA/BRD) ──> R1-M6 (review/release)
C-18 ──────┘        ▲                    ▲   ▲               ▲
O-06/O-13 ──(if C-01 = Platform Admin)┘  │   │     O-06, O-11, O-13, S-02, O-32
                    O-01 (provider) ─────┘   │
                    O-15 (real documents) ───┘
```

The model gateway (fake provider) is ready now and does not wait for any decision. A real provider adapter waits for O-01.
