# MVP Programme Report — 2026-11-04

Scope authority: PRD v0.7 (product), TDD v1.0.4 (architecture), decision register. Neither document was modified.
Originally written 2026-11-04 (local evidence only). **Updated 2026-11-05:** branches published, PRs #3–#5 opened,
CI and the Docker Compose smoke test run, model gateway added. The 2026-11-05 section below supersedes earlier
"not pushed / CI not run" statements; the original text is kept for history.

## Update 2026-11-05

### Milestone numbering (PRD vs repository)

The repository's "Milestone N" labels count implementation increments; the PRD's R1-Mx labels are product milestones.
Branch names are historical and are not renamed.

| PRD milestone | Implementation workstream | Branch | PR | Scope | Status (2026-11-05) | Verification evidence |
|---|---|---|---|---|---|---|
| R1-M0 | Milestone 1 | `claude/keen-noether-o7qkue` | [#1](https://github.com/Tony079/agentic-sdlc-platform/pull/1) → `main` | Skeleton, auth, tenancy, RLS, audit, Compose, CI | COMPLETED AND VERIFIED (CI green, not merged) | CI runs 1, 2 |
| R1-M1 (part 1) | Milestone 2 | `claude/milestone-2-requirements` | [#2](https://github.com/Tony079/agentic-sdlc-platform/pull/2) → #1 | Requirement submission | COMPLETED AND VERIFIED (CI green, not merged) | CI runs 3, 4 |
| R1-M1 (part 2) | Milestone 3 | `claude/milestone-3-outbox-routing` | [#3](https://github.com/Tony079/agentic-sdlc-platform/pull/3) → #2 | Outbox, job queue, worker login, routing, definition R1-v1 | COMPLETED AND VERIFIED (CI green, not merged) | CI runs 11, 14 on `bf4318f` |
| R1-M2 | Milestone 4 | `claude/milestone-4-documents` | [#4](https://github.com/Tony079/agentic-sdlc-platform/pull/4) → #3 | Upload, encrypted storage, validation, ClamAV, quarantine | COMPLETED AND VERIFIED (CI green incl. real ClamAV, not merged) | CI runs 12, 15 on `ab1ac05` |
| R1-M3 | Milestone 5 | `claude/milestone-5-document-processing` | [#5](https://github.com/Tony079/agentic-sdlc-platform/pull/5) → #4 | Extraction, chunks, retries (gate O-15) | COMPLETED AND VERIFIED for synthetic documents; real documents BLOCKED BY OWNER DECISION (O-15) | CI runs 13, 16 on `7e98a34` |
| R1-M5 (foundation) | Model gateway | `claude/model-gateway` (local, not pushed) | — | Provider-neutral gateway + fake provider | IMPLEMENTED, LOCAL TESTS PASSING (CI not run) | Local `mvn verify`: 175 tests |
| R1-M4 | — | — | — | Repository registration, snapshot, inventory | BLOCKED BY OWNER DECISION (C-01/O-05, C-18; O-06/O-13 if admin) | Readiness spec |
| R1-M5 | — | — | — | Agent runtime, BA agent, BRD, evidence checker | BLOCKED BY OWNER DECISION (needs R1-M4; O-01 for real model; O-15 for real documents) | Readiness spec |
| R1-M6 | — | — | — | Review, approvals, clarifications, client view, G1 | BLOCKED BY OWNER DECISION (O-06, O-11, O-13, S-02, O-32) | Readiness spec |

Readiness package for R1-M4..R1-M6: `docs/architecture/r1-m4-m6-implementation-readiness.md`.

### Publication

- Pre-push checks: clean tree; linear ancestry `1bc15f7` → M3 → M4 → M5 with no merge commits and no changes to
  PRD/TDD or changesets 0001–0009; remote PR #1/#2 heads unchanged (`652fa9a`, `1bc15f7`); gitleaks v8.21.2 over all
  local history (24 commits): no leaks.
- Pushed (normal push, no force): the three branches. Opened stacked PRs #3 (base #2's branch), #4 (base #3's branch),
  #5 (base #4's branch). None merged. PR #1 and PR #2 not modified or retargeted.
- CI fix: see V-CI-1 in the decision register; propagated with merge commits `ab1ac05` (into M4) and `a62c738`
  (into M5); an M5 Compose step for extraction added (`7e98a34`).

### CI results (GitHub Actions workflow "CI")

| Run | Event | Branch / SHA | Backend | Frontend | Secret scan | Compose smoke | Conclusion |
|---|---|---|---|---|---|---|---|
| 5, 8 | push, PR #3 | M3 `8340877` | pass | pass | pass | **fail** (M3 step, V-CI-1) | failure |
| 6, 9 | push, PR #4 | M4 `72c5e8d` | pass | pass | pass | **fail** (same step; ClamAV step not reached) | failure |
| 7, 10 | push, PR #5 | M5 `c186877` | pass | pass | pass | **fail** (same step) | failure |
| 11, 14 | push, PR #3 | M3 `bf4318f` | pass | pass | pass | pass | **success** |
| 12, 15 | push, PR #4 | M4 `ab1ac05` | pass | pass | pass | pass (real ClamAV: clean file READY_FOR_EXTRACTION, EICAR REJECTED_MALWARE, download 409) | **success** |
| 13, 16 | push, PR #5 | M5 `7e98a34` | pass | pass | pass | pass (all steps incl. extraction of a synthetic Markdown file into chunks) | **success** |

Compose smoke steps verified in CI on `7e98a34`: login and cross-tenant 404; requirement create/edit/submit; worker
routing to MANUAL_INTAKE; upload, **real ClamAV** scan and download, EICAR blocked (409 on download); extraction to
PROCESSED with stored chunks. **Not verified in CI:** scanner *outage* fail-closed behaviour (covered only by
integration tests with the protocol-level clamd test double); scanner health alerting (log/metric only, C-17).

### Security checks (2026-11-05)

- gitleaks: local v8.21.2 over all history — no leaks; CI secret-scan job green on every run.
- Secrets: database, storage and seed secrets come only from the environment (`.env` locally, random per CI run);
  `.env.example` holds placeholders only. No GitHub App private key exists in the code yet (R1-M4 not built).
- Containers: backend runs as the non-root `sdlc` user; web is unprivileged nginx; no `privileged`, capabilities or
  host mounts in Compose; database, scanner and worker ports are not published; web bound to 127.0.0.1.
- Findings: **SEC-M3-01** (generated default user/password logged; fixed on `claude/model-gateway`), SEC-REVIEW-1..3
  (extractor isolation → O-15; static storage key → O-12/C-13; ClamAV container defaults → O-03).

### Model gateway (local branch `claude/model-gateway`)

See decision register section 7 (D-GW-01..10). Changeset `0016-model-gateway`; module `modelgateway`; tests
`ModelGatewayIT` (14), `ModelProviderRegistryTest` (3), ArchUnit rule `modelGatewayCannotReachStateChangingModules`,
`NoDefaultUserIT` (2). Full local suite on this branch: **175 tests, 0 failures** (PostgreSQL 16.15). No real provider
(O-01); tool calling deferred to the R1-M5 agent runtime (D-GW-09).


## G. Readiness verdict: **MVP PARTIALLY COMPLETE**

PRD Release 1 milestones R1-M0 to R1-M3 are implemented and verified locally. R1-M4 to R1-M6 are **not started**.
Their core features depend on open owner decisions:

- **R1-M4 (GitHub repository registration).** Needs C-01/O-05 (who registers a repository) and staff accounts (O-06, O-13).
- **R1-M5 (model gateway, BA agent, BRD).** Analysis is reachable only through repository validation (T08). The real model provider is O-01.
- **R1-M6 (review, approval, client release).** Needs the Business Reviewer role (O-06).

Production readiness is **not** claimed. O-15 blocks real client documents. O-12 and C-13 (production object store), the Compose smoke run in CI, and Gate G1 are all outstanding.

| Level | Status |
|---|---|
| Implementation complete | R1-M0..R1-M3 yes; R1-M4..R1-M6 no |
| Integration verified | Locally: real api + worker + migrate processes and the packaged jar, on PostgreSQL 16.15. Docker Compose: **NOT RUN** (no Docker daemon here) |
| Security verified | Database privilege, RLS, fencing and fail-closed tests pass locally. No independent review. S-01, S-02, S-06 (archive) open |
| Production readiness | **Not verified** |

## Scope conflict found (C-19)

The programme brief lists effort estimation, pricing, estimate acceptance, client scope approval, the Architect Agent, TDD generation and architecture approval as MVP items. PRD v0.7 places them in Release 2 and Release 3. The PRD prevails, so none of them were built, and the ArchUnit scope guard still forbids those packages.

## A. Milestone completion report

| Milestone (PRD) | Repo milestone / branch | Status | Implemented | Remaining gaps |
|---|---|---|---|---|
| R1-M0 skeleton, auth, tenancy, audit, Compose, CI | M1 · `claude/keen-noether-o7qkue` · PR #1 (open) | Done, CI green | See PR #1 | Staff auth (O-06, O-11), bootstrap (O-13) |
| R1-M1 requirement submission | M2 · `claude/milestone-2-requirements` · PR #2 (open, stacked) | Done, CI green | Eight fields, keys, revisions, ETag, idempotency, T01/T29 | — |
| R1-M1 workflow definition v1, job queue, outbox | **M3** · `claude/milestone-3-outbox-routing` (local) | Done, local only | Outbox; queue (leases, heartbeats, fencing, retries, dead-letter, recovery); `sdlc_worker`; T02/T03; `workflow_definition` R1-v1 with startup check; stage executions; cancellation follow-up; Q-M3-5 edit policy | T03 reviewer notification (O-06); REPO_VALIDATE handler (R1-M4) |
| R1-M2 upload, private storage, validation, scan, quarantine | **M4** · `claude/milestone-4-documents` (local, on M3) | Done, local only | Multipart upload with limits; encrypted private store; content validation; ClamAV INSTREAM scan, fail closed; malware security event; download after a clean scan; DRAFT removal; UI panel | Preview (C-04); versioning after submission; production store (O-12) |
| R1-M3 document processing | **M5** · `claude/milestone-5-document-processing` (local, on M4) | Done, local only, **behind gate O-15** | Isolated extractor process (PDF, DOCX, TXT, MD); validated output; chunks and full-text index; no-OCR outcomes; 3 attempts then final | Container-level isolation and the M3S spike (O-15) |
| R1-M4 GitHub App, snapshot, Phase A inventory | — | **Not started** | — | Blocked: C-01/O-05, O-06/O-13, C-18; needs GitHub App credentials |
| R1-M5 gateway, agent runtime, BA agent, BRD, evidence checker | — | **Not started** | — | Workflow entry needs R1-M4; O-01 (provider), O-08, O-09 |
| R1-M6 review, clarifications, client view, notifications, G1 | — | **Not started** | — | Blocked: O-06 (Business Reviewer), O-16, O-21, O-22, C-05, C-09, C-16, O-32 |

Files changed on M3 to M5, compared with the Milestone 2 head: 105 files, about 7,900 lines added, in 11 commits.

## B. Architecture and implementation

- **Processes.** One image runs three processes:
  - `api`: login `sdlc_app`, serves HTTP.
  - `worker`: profile `worker`, login `sdlc_worker`, no HTTP.
  - `migrate`: one-shot, login `sdlc_migrator`, acting as `sdlc_owner`.
- **Modules.** New this programme: `outbox`, `queue`, `document`, `storage`, `scanner`, `docprocessing`. Modules interact only through `api` packages, enforced by ArchUnit.
- **Workflow.** The engine is the only writer of workflow tables, and every state-changing command takes the instance lock first. Submission writes `RequirementSubmitted` in the same transaction. The worker's routing consumer then performs T02 (create the stage, enqueue REPO_VALIDATE) or T03. The database refuses T02/T03 when performed by a user actor. The stored definition R1-v1 must match the compiled table, or the process refuses to start.
- **Outbox.** Dispatch is per tenant, inside that tenant's RLS context.
  - The worker takes one event with `FOR UPDATE SKIP LOCKED`, and only if no earlier undelivered event exists for the same aggregate.
  - Each consumer runs under its own savepoint together with its `processed_event` row.
  - The event is then marked published, retried with backoff, or dead, all in the same transaction.
  - Dead events are final, audited, alerted, and block only their own aggregate. There is no recovery API (Q-M3-6).
- **Job queue.**
  - Claiming uses two phases: lock candidates with SKIP LOCKED, then decide using database time read after the lock.
  - The lease epoch is the fencing token; heartbeats extend the lease and the runtime is capped.
  - Commits are fenced: lock the row, check owner, epoch and expiry, then write the result and finish in one transaction.
  - Failures are retryable (budgeted), fatal, or dependency-unavailable (no budget used).
  - Expired leases are recovered and counted as infrastructure failures.
  - Final jobs and closed attempts are immutable, enforced by triggers.
- **Documents.**
  - The API streams the file into encrypted storage and records the version as QUARANTINED. It cannot set state or scan columns.
  - The worker decrypts into a private workspace, verifies hash and size, validates the structure, scans, and commits under the lease.
  - The extractor is a separate JVM with an empty environment and resource limits. The coordinator validates all of its output before anything is stored.
- **API additions.**
  - `GET|POST /api/v1/requirements/{id}/documents`
  - `GET /api/v1/requirements/{id}/documents/{doc}/content`
  - `DELETE /api/v1/requirements/{id}/documents/{doc}`
  - New errors: `409 KIND_CHANGE_NOT_ALLOWED`, `DOCUMENTS_LOCKED`, `DUPLICATE_DOCUMENT`, `DOCUMENT_NOT_AVAILABLE`; `413 FILE_TOO_LARGE`.

## C. Database report

All new changesets; 0001–0009 untouched.

| Changeset | Contents |
|---|---|
| 0010-outbox | `outbox_event` (identity seq, catalogue CHECK, 2 KB payload with `v`), `processed_event`, terminal-guard trigger |
| 0011-job-queue | `job_type` (seeded: REPO_VALIDATE, DOCUMENT_SCAN, DOCUMENT_EXTRACT, BA_ANALYSIS), `job`, `job_attempt` (one open attempt), policy-copy, immutability and epoch triggers |
| 0012-m3-workflow | `workflow_definition` R1-v1; definition FK; transition CHECKs replaced (T02, T03, T29 from routed states); engine-actor CHECK; `stage_execution` |
| 0013-m3-rls-and-grants | Forced RLS on 5 tables; `sdlc_app` may insert the 5 outbox producer columns only; `sdlc_worker` column-limited grants |
| 0014-document | `document`, `document_version` (PRD 6C states); named CHECK `document_version_clean_scan_required`; immutable scan result and terminal states; live-duplicate unique index; grants (the API cannot write state or scan columns) |
| 0015-document-extraction | `document_extraction`, `document_chunk` (generated `tsvector`, GIN index); worker-only grants |

- **Roles.** `sdlc_worker` was added to the bootstrap (idempotent; its password comes from `SDLC_DB_WORKER_PASSWORD`). Existing databases must re-run the bootstrap before `migrate` (D-M3-10).
- **Function checks (asserted by `SchemaSecurityIT`).** No `SECURITY DEFINER` functions; no PUBLIC EXECUTE on `app` functions; every PL/pgSQL function pins `search_path`.
- **Migration verification.**
  - Clean installs: run on every test JVM (bootstrap, then the real migrate process, then `sdlc_app`/`sdlc_worker` tests).
  - Upgrades: 0010–0015 applied on top of an existing 0001–0009 database (`sdlc_e2e`).
  - Rollback scripts: none (forward-only, as in M1/M2).

## D. Test report (executed in this session)

| Suite | Total | Passed | Failed | Skipped |
|---|---|---|---|---|
| Backend unit (Surefire) | 42 | 42 | 0 | 0 |
| Backend integration on real PostgreSQL 16.15 (Failsafe) | 113 | 113 | 0 | 0 |
| Frontend (Vitest), plus lint, typecheck and build | 22 | 22 | 0 | 0 |

The backend total grew from 83 at Milestone 2 to 155.

**New suites:**
- `OutboxDispatcherIT` (7): savepoint isolation, per-aggregate order, dead-letter and audit, permanent failures, crash before commit, 4 concurrent dispatchers × 45 events delivered exactly once in order.
- `JobQueueIT` (13): 6 concurrent workers × 40 jobs with each job claimed exactly once; live lease never given away; an expired worker cannot commit; an old epoch is fenced off; infrastructure dead-letter; retry with backoff then dead; fatal failures; deferral; cancellation fencing; tenant isolation; the API login cannot claim; crash recovery with a single effect.
- `RequirementRoutingIT` (8), `DocumentApiIT` (10), `ExtractionIT` (5) and `SchemaSecurityIT` (now 9).
- Unit tests for the store, validator, scanner, extractor and definition verifier.

**Mandatory security regressions covered:**
- Cross-tenant read and modify.
- No RLS bypass.
- The API cannot mark events published or write `processed_event`.
- Unauthorised logins and workers cannot claim jobs.
- Expired workers and old epochs cannot commit.
- Duplicate events and jobs produce no duplicate effects.
- A scanner failure fails closed.

**Not applicable yet:** approvals, stale inputs, blocking clarifications, prompt-injection and tool authorisation, client approval versus release (R1-M5/M6 not built).

**Found and fixed by tests before commit:**
- V-M4-1: a fail-open NULL in the scan CHECK.
- V-M5-1: the extractor class path when started from the packaged jar.

**Local end-to-end with the real jar** (api + worker + migrate on PostgreSQL 16.15; clamd replaced by a Python INSTREAM stand-in):
- Routing to REPO_VALIDATING and to MANUAL_INTAKE.
- Upload, then scan, then download of a clean file; EICAR rejected with 409 and an alert.
- Markdown upload processed into chunks.

**NOT RUN:**
- Docker Compose and the CI smoke steps for M3–M5 (no Docker here; not pushed).
- The real ClamAV daemon (CI only).
- gitleaks (CI only).
- Browser E2E for the new screens.

## E. Open decision report

| ID | Description | Owner | Affected functionality | Current restriction | Gate |
|---|---|---|---|---|---|
| O-06 | Staff access model | SO, PO | Business Reviewer, Auditor, Platform Admin; T03 notification, T04/T05, review (R1-M6) | No staff accounts; reviewer features not built | Implementation blocker (R1-M6) |
| O-11 | Staff MFA | SO | Staff login | Not built | Before staff login |
| O-13 | Provisioning and two-admin bootstrap | PO, SO | Admin bootstrap, provisioning, dead-event and dead-job recovery | No admin APIs; no recovery path (Q-M3-6) | Implementation blocker |
| O-32 | Admin access-grant policy | PO, SO | AC-EXT-37 | No admin access to tenant content | Release (G1) |
| C-07 | Platform Admin cancel in MANUAL_INTAKE | PO | T29 by admin | Proposed only; owner cancel works, admin cancel not built | Before admin features |
| C-18 | Requirement visibility (approver) | PO | Repository link by the Client Approver (M2-C06), documents | Owner-only visibility everywhere | Blocker for R1-M4 |
| D-M1-06 | Liquibase 5 licence | Owner | Migration tool | Liquibase 5.0.4 in use, analytics off | Before production |
| CP-2 | SQL archive baseline | Owner | Archive reuse | Archive not imported | Archive use only |
| S-01 | Archive: app role can purge audit | SO | Retention | Our schema has no purge; audit is append-only | Before retention |
| S-02 | Archive: app role writes role assignments | SO | Staff roles | No platform role tables | Before O-06/O-13 work |
| S-06 | Archive: REPO_VALIDATING retry safeguard | SO, PO | Admin retry | Our schema has no admin-retry path; T30 refused by the definition check | Before any admin retry |
| O-15 | Extractor isolation spike (M3S) | SO, AO | Processing real documents | Extraction gate off by default; process isolation only | **Blocks client onboarding** |
| O-12 / C-13 | Production object store and encryption | SO, AO | Storage | Encrypted file store for development and CI only | Production |
| O-03 | Production scanner | SO | Scanning | ClamAV for development | Production |
| C-01 / O-05 | Repository registrant | PO, SO | R1-M4 | Not built | Implementation blocker |
| O-01 | LLM provider | PO | R1-M5 | Not built | Onboarding (a fake provider is allowed) |
| C-04 | Document preview | AO, SO | FR-UPL-11 | Download only | Before acceptance |
| C-17 | Admin alert channel | PO, SO | AC-EXT-33, dead-event alerts | Security events plus `ALERT` log lines and Micrometer gauges; no HTTP metrics endpoint | Before acceptance |
| C-19 | Brief versus PRD scope | PO | Estimation, TDD stages | Not built (PRD R2/R3) | Product decision |

## F. Git report

- **Existing PRs, preserved.** [PR #1](https://github.com/Tony079/agentic-sdlc-platform/pull/1) (head `652fa9a` → `main`) and [PR #2](https://github.com/Tony079/agentic-sdlc-platform/pull/2) (head `1bc15f7` → the Milestone 1 branch). Both are open, unchanged, not merged and not retargeted.
- **New local branches, stacked and not pushed:**
  - `claude/milestone-3-outbox-routing`: `0964b75`, `3f50b03`, `9fa093a`, `8340877`.
  - `claude/milestone-4-documents`: `b2645db`, `6a2427d`, `414929e`, `72c5e8d`.
  - `claude/milestone-5-document-processing`: `1f44314`, `16a3aae`, `0759dfa`, plus this report.
- No force-push, no rebase of reviewed branches, no new PRs. Pushing and PR creation await separate authorisation.
