# Final Project Completion Report — Agentic SDLC Platform Release 1 (2026-11-08)

## 1. Executive summary

The platform now implements one integrated Release 1 path end to end, and that path is exercised by automated tests
against PostgreSQL 16 and the real worker:

1. A client creates and submits a requirement with documents.
2. A Client Approver authorises a GitHub repository through a one-time code.
3. A staff member with MFA registers it.
4. The worker verifies the repository, pins the commit, snapshots and indexes it, and scans and extracts the documents.
5. The BA agent runs two phases through the provider-neutral model gateway.
6. A trusted commit step verifies every citation and creates a BRD version.
7. A Business Reviewer acknowledges flags and approves the exact version.
8. The engine releases it to the client in a separate step.
9. The client can read it, without source excerpts, and request changes, which produces a new version.

**The project is not 100 % complete and not production-ready.**
- Every external integration was tested only with fakes: GitHub with an in-process fake server, the LLM with the deterministic fake provider.
- Docker Compose was not started here (no Docker daemon).
- CI has not run on the new branches (nothing is pushed).
- Several owner decisions block production (section 16).
- Three acceptance criteria are not implemented: clarifications, the clarification inbox, and reviewer document decisions.

## 2. Final architecture

See `docs/architecture/system-overview.md` and `docs/architecture/end-to-end-flows.md`. In summary:
- A modular monolith (Java 21, Spring Boot 4.1.2) with API, worker, migrate and provision processes from one image.
- PostgreSQL 16 with forced RLS on all 38 tenant tables, a transactional outbox and a leased job queue.
- A React/TypeScript SPA.
- No RabbitMQ or Redis: they are not part of the TDD architecture.

## 3. Completed milestones

| Milestone | State |
|---|---|
| Foundation, requirements, outbox/jobs, documents, extraction, model gateway (PRs #1–#6) | Implemented and tested earlier; CI green on PR #6 |
| R1-M4 repository registration and staff identity | Implemented; tested with a fake GitHub |
| R1-M5 BA agent, input sets, evidence checker, BRD | Implemented (core); tested with the fake model provider |
| R1-M6 review, approval, release, client view, client changes, notifications | Core implemented and tested; clarifications, document decisions, export, version diff, admin features **not built** |
| Retention, admin retry, admin access grants | Not built (blocked by open decisions) |

## 4. Acceptance-criteria traceability

Full matrix: `docs/testing/verification-matrix.md`.

| Status | Count of 60 |
|---|---|
| VERIFIED | 51, of which 13 are qualified (partial, fake service or limited scope) |
| IMPLEMENTED, NOT VERIFIED | 2 |
| BLOCKED | 4 |
| NOT IMPLEMENTED | 3 |

## 5. Features implemented in this session (branch `feature/r1-m5-m6-completion`)

- **Guard 9.4 and T11:** analysis starts only when every document is processed or excluded, the repository is pinned and the snapshot is indexed.
- **Frozen input set:** a SHA-256 over the revision, commit, snapshot, document versions and correction notes.
- **BA_ANALYSIS:**
  - Phase A and Phase B prompts are versioned and hash-registered.
  - Data blocks are untrusted and kept separate from the instructions.
  - Snapshot excerpts are line-numbered.
  - Output is schema-checked by the gateway.
  - Tx P stores a pending result that is never approvable.
- **BA_COMMIT_RESULT (Tx C):**
  - Checks stage identity, state and input hash; a stale result leads to T15 or T14.
  - Re-validates the schema.
  - Verifies citations: repository path, line range and quote, with a range hash; document version, chunk and quote; a lexical support check.
  - A FACT whose citation fails becomes an ASSUMPTION; an unsupported statement is FLAGGED.
  - Writes the immutable BRD version, then T18 and T20.
- **Failure handling:** T19 on a dead BA job. COST_CAP is fatal. With no provider configured, the job waits.
- **Business Reviewer capability:** a two-person grant, used with an assigned active tenant.
- **Review:**
  - a review queue and detail with quotes, and flag acknowledgement;
  - approval T1 bound to the content and input hashes, with VERSION_CHANGED, STALE_INPUTS, FLAGS_NOT_ACKNOWLEDGED, SELF_APPROVAL_FORBIDDEN and APPROVAL_CONFLICT checks, and four-eyes enforced by a database trigger;
  - revision (T22, then T24) and rejection (T23).
- **Release:** automatic T25 in the worker, with re-checks; the approval is invalidated when inputs change or a new version supersedes it.
- **Client:** sees only the released BRD (404 before release), with no excerpts; can request changes (T27, then T28, then v2); receives in-app notifications.
- **Frontend:** client BRD panel and change request, notifications page and badge, staff review queue and decision page.

## 6. Database migrations

Changesets 0001–0021 are forward-only Liquibase changesets; no applied changeset was edited. This session added:
- **0020-analysis-brd:** input sets, pending results, BRD tables, immutability guards, prompts, R1-M5 transitions, grants.
- **0021-review-release:** reviewer capability, approval, flag acknowledgements, client change requests, notifications, R1-M6 transitions, STAFF actor type.

`SchemaSecurityIT` checks forced RLS on every tenant table, the absence of SECURITY DEFINER and PUBLIC EXECUTE, and the per-login grants.

## 7. API endpoints added in this session

| Endpoint | Caller |
|---|---|
| `GET /api/v1/staff/reviews` | Business Reviewer |
| `GET /api/v1/staff/brd-versions/{id}` | Business Reviewer |
| `POST /api/v1/staff/brd-versions/{id}/flags/{itemKey}/acknowledge` | Business Reviewer |
| `POST /api/v1/staff/brd-versions/{id}/approve` | Business Reviewer |
| `POST /api/v1/staff/brd-versions/{id}/request-revision` | Business Reviewer |
| `POST /api/v1/staff/brd-versions/{id}/reject` | Business Reviewer |
| `GET /api/v1/requirements/{id}/brd` | Requirement owner |
| `POST /api/v1/requirements/{id}/client-changes` | Requirement owner |
| `GET /api/v1/notifications` | Client users |
| `POST /api/v1/notifications/{id}/read` | Client users |

Earlier endpoints are documented in `docs/architecture/repository-integration.md` and the TDD.

## 8. Frontend screens and journeys

- **Client:** sign-in, projects, requirement form and history, documents, repository link code, approver redemption, BRD view and change request, notifications.
- **Staff:** sign-in with TOTP enrolment or verification, organisation selection, repository registration, review queue and decision page.
- **Tests:** Vitest 33/33, covering the critical components and journeys. There are no browser end-to-end tests: Playwright was not run against a live stack.

## 9. Agent and model-provider architecture

- Agents depend only on `ModelGateway`. Prompts are classpath resources whose SHA-256 is registered in `prompt_version`.
- Each call is recorded in `model_call_record` with provider, model, prompt version, tokens, cost and usage source.
- Only the `fake` provider exists. The default is `none`, under which nothing leaves the platform and analysis waits. A real provider is blocked on O-01.
- Tool calling is not available in the gateway, so context assembly replaces a tool registry (D-R5-03).

## 10. Security controls and evidence

| Control | Evidence |
|---|---|
| Tenant isolation (forced RLS, pooled-connection leak test) | `SchemaSecurityIT`, `RlsIsolationIT`, `RlsPoolLeakIT`, cross-tenant cases in `LinkRequestIT`, `RepositoryIntegrationIT`, `ReviewIT` |
| Staff MFA, TOTP replay protection, session re-validation | `StaffAuthIT` |
| Two-person grants and the S-02 runtime-login limits | `StaffProvisioningIT` |
| C-18 uniform 404 responses | `LinkRequestIT` |
| GitHub token scope, host allow-list, redirect rules, size caps | `GitHubBrokerTest` |
| Archive limits and path traversal | `SnapshotArchiveTest` |
| XXE-safe pom parsing | `IndexerTest` |
| Evidence integrity (forged citations) | `EvidenceVerifierTest`, `AnalysisIT` |
| Prompt-injection separation | `AnalysisIT` |
| Four-eyes approval, approval immutability, client view without excerpts | `ReviewIT` |
| Secrets scanning | gitleaks: full history clean (3 reviewed fingerprints in `.gitleaksignore`) |
| Frontend dependencies (`npm audit`) | Production 0 vulnerabilities; development tooling 10 moderate (`minimatch` via eslint), not fixed |

**Not verified:**
- No container image scan or Maven dependency scan was run (offline).
- No backup or restore drill was run.
- No penetration test was run.

The detailed self-review is in `docs/reports/r1-m4-implementation-report-2026-11-07.md` section 4.

## 11. Test execution results (executed 2026-11-07 on `c297a8b`'s code)

| Suite | Result |
|---|---|
| Backend `mvn verify` (PostgreSQL 16) | **247 tests, 0 failures, 0 errors, 0 skipped** (75 unit, 172 integration, 40 classes) |
| Frontend | Vitest **33/33**; `tsc --noEmit` OK; ESLint OK; `vite build` OK |
| gitleaks | no leaks (full history) |

## 12. Docker Compose verification

`docker compose config` is valid, including the `tools` profile, and a missing `SDLC_MFA_KEY` is refused. **The stack was
not started**, because no Docker daemon is available in this environment.

## 13. CI execution

CI is green on PR #6 (`e3feb84`), as recorded earlier. **CI has not run** on `feature/r1-m4-repository-registration` or
`feature/r1-m5-m6-completion`, because neither has been pushed (pushing needs your approval).

## 14. External integrations actually verified

**None against a real service.**
- GitHub: tested with an in-process fake.
- LLM: tested with the deterministic fake provider.
- ClamAV: real in CI on PR #4; not re-run here.
- Object storage: local encrypted file store only.

## 15. Known limitations

- **Not built:**
  - clarifications (T16/T17) and their inbox;
  - reviewer document decisions (T10/T12/T13);
  - manual-intake resume (T04/T05);
  - admin retry (T26);
  - critical-finding records;
  - version diff;
  - BRD export;
  - manual release mode;
  - proactive stale cancellation when a document changes;
  - model tool calling;
  - retention and deletion;
  - an alerting stack.
- **Accepted risks:**
  - JavaParser runs inside the worker JVM;
  - staff TOTP codes appear in provisioning command arguments;
  - the storage and MFA keys are static, with no rotation;
  - login and MFA throttles are held in memory.

## 16. Remaining owner decisions

| Decision | Effect |
|---|---|
| O-01 / O-09 | Model provider and budget values |
| O-03 | Production scanner |
| O-12 / C-13 | Production store and KMS |
| O-13 | Production administrator ceremony |
| O-15 | Extractor isolation |
| O-25 / O-02 / O-04 / O-23 | Retention |
| O-27 / S-06 | Retry after failure |
| O-32 | Admin access to tenant content |
| O-16 | Release mode (AUTOMATIC used) |
| O-08 | Flag handling (per-item acknowledgement used) |
| O-21 / O-22 | Further M6 product details |
| C-05 | Reviewer clarifications |
| C-07 | Platform Admin cancellation in MANUAL_INTAKE |
| C-09 | Who may request client changes (owner used) |
| C-16 | Version diff priority |
| C-17 | P0 alert channel |
| D-M1-06 | Liquibase licence |
| CP-2 | SQL archive baseline |

Flagged implementation decisions for review: D-M4R-04/10/11/16, D-R5-01/03/06/08, D-R6-02/06.

## 17. Production deployment instructions

See `docs/operations/production-deployment.md`. **Do not deploy to production**: the gates in its first table are open.

## 18. Branches and commits

| Branch | Head | State |
|---|---|---|
| PRs #1–#6 | `652fa9a`, `1bc15f7`, `bf4318f`, `ab1ac05`, `7e98a34`, `e3feb84` | Unchanged; local equals remote |
| `feature/r1-m4-repository-registration` (local) | `92fd9df` | 7 commits on `e3feb84` |
| `feature/r1-m5-m6-completion` (local) | this report's commit, on `c297a8b` | Commits on top of `92fd9df`: `7d901f5` (baseline), `f7b5258` (R1-M5), `cd42a47` (R1-M6), `33beaa1` (frontend), `c297a8b` (docs) |

No push, merge, PR, rebase or force-push was performed.

## Final checklist

| Area | State |
|---|---|
| Requirement → BRD → review → release → client view path | VERIFIED (fake GitHub, fake model) |
| Tenant isolation and RLS | VERIFIED |
| Staff MFA and two-person provisioning | VERIFIED (development/test bootstrap); production ceremony BLOCKED (O-13) |
| Evidence integrity and prompt-injection separation | VERIFIED |
| Real GitHub App integration | IMPLEMENTED, NOT VERIFIED (needs an App and credentials) |
| Real LLM provider | BLOCKED (O-01) |
| Docker Compose runtime | IMPLEMENTED, NOT VERIFIED (no Docker daemon) |
| CI on the new branches | NOT RUN (needs push approval) |
| Production storage and KMS, scanner, retention, admin features | BLOCKED (owner decisions) |
| Clarifications, document decisions, export, diff | NOT IMPLEMENTED |
