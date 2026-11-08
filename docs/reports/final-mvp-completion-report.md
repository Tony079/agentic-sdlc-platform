# Final MVP Completion Report — Agentic SDLC Platform (2026-11-08)

| | |
|---|---|
| Branch | `feature/mvp-production-readiness`, created from `feature/r1-m5-m6-completion` at `c4c5f61`. Local only |
| Push, merge, PR, deploy | None |
| PR branches #1–#6 | Unchanged; local equals remote: `652fa9a`, `1bc15f7`, `bf4318f`, `ab1ac05`, `7e98a34`, `e3feb84` |
| Authorities | PRD v0.7, TDD v1.0.4, `docs/decisions/decision-register.md` (neither the PRD nor the TDD was modified) |

## 1. Overall completion

| Area | Status | Evidence |
|---|---|---|
| Acceptance criteria (60) | **37 VERIFIED, 18 VERIFIED (qualified), 1 IMPLEMENTED NOT VERIFIED, 1 BLOCKED, 3 NOT COMPLETE** | `docs/testing/mvp-acceptance-criteria-matrix.md` |
| Workflow transitions T01–T29 | 25 of 29 implemented; T04, T05, T26 missing; T08 replaced by T11 (D-R5-01) | `TransitionTable`, database CHECKs, `DefinitionVerifier` |
| Backend tests | 263 passed, 0 failed (75 unit, 188 integration) | `mvn verify`, PostgreSQL 16 |
| Frontend | 36/36 Vitest; `tsc`, ESLint and build OK | — |
| Real external integrations | **0 verified** (GitHub, LLM, production scanner, production store) | `docs/testing/external-integration-verification.md` |
| Production-readiness gates | 17 OPEN | `docs/operations/production-readiness-checklist.md` |
| **Production verdict** | **NOT READY** | §8 |

**The MVP is not 100 % complete,** and this report does not claim it is.

## 2. Milestone status

| Milestone | Status |
|---|---|
| R1-M0/M1 foundation, tenancy, auth, audit | Complete, tested (PR #1) |
| R1-M2 requirements, workflow | Complete, tested (PR #2) |
| R1-M3 outbox, jobs, routing | Complete, tested (PR #3) |
| Documents, storage, scan | Complete, tested (PR #4); production store and scanner open |
| Extraction | Complete, tested (PR #5); gated off by O-15 |
| Model gateway | Complete with the fake provider (PR #6 plus tool calling here); no real adapter (O-01) |
| R1-M4 repository registration and staff identity | Complete against fake GitHub (local); real GitHub unverified |
| R1-M5 BA agent, BRD, evidence | Complete with the fake model (local) |
| R1-M6 review, release, client view | Complete (local); manual release (O-16), admin retry T26 (O-27) and manual-intake resume/close (T04/T05) not built |
| **This phase:** clarifications, document decisions, staleness, retention, diff, tool calling, UI | Complete as described in §3 |

## 3. Implementation summary (this phase)

| Commit | Content |
|---|---|
| `e317aac` | **Clarification lifecycle (FR-CLR):**<br>• Phase B prompt v2 with `clarifications[]`; blocking questions pause the commit (T16) and resume on answer (T17), with the paused execution SUPERSEDED.<br>• Reviewer-raised clarifications block approval.<br>• Client and staff answer, reject, request-information, reopen and reassign endpoints; notifications.<br>• **Reviewer document decisions** (T10/T12/T13) and owner acknowledgement of UPLOAD_FAILED.<br>• **Proactive staleness:** an upload during analysis cancels and restarts (T14); an upload during review makes the version STALE and invalidates approvals.<br>• Changeset 0022 |
| `64b63ee` | **Retention and tenant deletion (FR-RET, S-01):**<br>• New login `sdlc_retention` and one-shot `retention` profile (`apply`, `set-policy`, `delete-tenant-data`).<br>• Changeset 0023: `retention_policy` with RLS, DELETED/PURGED final states, narrow grants.<br>• Content-free audit; Compose `tools` service; runbook |
| `7be3c39` | **BRD version comparison** (TDD 16.8, statement level by item key): reviewer endpoint and UI.<br>**Frontend:** client clarification inbox; reviewer inbox (clarifications and document exclusion) |
| `764f03e`, `ea7b833` | **Tool calling in the model gateway (FR-LLM-01):** registry bound per request, refusal of unregistered tools, result caps, round and call limits, results returned as untrusted data |
| (this commit) | Deliverables A–G; operations documents updated |

Diff against `c4c5f61`, before this documentation commit: 65 files, +3,454 / −48 lines.

## 4. Verification results (executed 2026-11-08)

| Check | Result |
|---|---|
| Backend `mvn verify` (local PostgreSQL 16 cluster) | 263 tests, 0 failures, 0 errors, 0 skipped |
| New or extended tests | `ClarificationIT` (7), `RetentionIT` (4), `ReviewIT` (diff assertions), `ModelGatewayIT` (+5 tool-calling tests), `SchemaSecurityIT` (new tables), `Clarifications.test.tsx` (3) |
| Frontend | Vitest 36/36; `tsc --noEmit` OK; ESLint OK; `vite build` OK |
| Retention one-shot | The packaged jar with `--spring.profiles.active=retention apply` ran against a migrated database and exited 0 |
| gitleaks (full history, 52 commits) | No leaks |
| `npm audit` | Production 0; development 10 moderate (`minimatch` via ESLint) |
| `docker compose config` | Valid |
| `docker compose up` | **Not run**: no Docker daemon in this environment |
| CI | **Not run on this branch**: pushing has not been authorised |
| Maven and container vulnerability scans | **Not run** |

**Correction:** an intermediate count of 264 and then 269 backend tests, given during this session, included stale report files from 2026-11-07. The count from this run's reports alone is 263.

## 5. Security assessment

Summary (full report: `docs/security/security-verification-report.md`):
- **Implemented and tested:** tenant isolation, separation of duties, audit immutability for runtime logins (S-01 now closed by `sdlc_retention`), evidence integrity, and safe tool calling.
- **Production blockers:**
  - static keys without KMS (O-12/C-13);
  - no production administrator ceremony (O-13);
  - no alert channel (C-17);
  - extractor isolation (O-15).
- **Not done:** independent review, penetration test, image and dependency scans.

## 6. External integrations: real versus mocked

| Integration | In tests | Real verification |
|---|---|---|
| GitHub App | In-process fake | **None.** Needs App id, private key, test organisation |
| LLM provider | Deterministic fake | **None.** No adapter; O-01 open |
| Malware scanner | ClamAV container in CI; fake clamd | Development/CI only (O-03) |
| Object store | Encrypted local file store | Development only (O-12) |
| Email | None by design | n/a |

## 7. Remaining blockers

1. **Owner decisions:** see `docs/decisions/mvp-final-decision-closure.md` §4:
   - O-01, O-12/C-13, O-13 (production), O-03, O-15;
   - O-02/O-04/O-23/O-25 (retention values), O-07/O-31, C-17, O-32, O-27/S-06, C-06/C-07, C-04, C-16, O-16, O-08, C-09, O-09;
   - RPO/RTO.
2. **Missing functionality:**
   - T04/T05 manual-intake resume and close (AC-EXT-28/42; T04 needs C-06);
   - admin retry T26 (O-27);
   - manual release mode (O-16);
   - admin access grants (O-32);
   - document preview (C-04);
   - backup expiry (FR-RET-06);
   - BRD export (FR-DOC-08, P1);
   - clarification escalation (FR-CLR-07, P1).
3. **Credentials:** GitHub App and test organisation; LLM provider key (after O-01).
4. **Infrastructure and evidence:**
   - Docker runtime smoke test;
   - CI run on this branch;
   - backup and restore drill;
   - production platform checks.
5. **Authorisations:** push, CI, merge, deploy.

## 8. Production verdict

**NOT READY**

The reasons:
- No real external integration is verified.
- Production key management and the administrator ceremony do not exist.
- Three acceptance criteria are not complete and one is blocked.
- The container runtime and CI have not been exercised on this code.
- Several owner decisions that govern production behaviour are open.

The code base is functionally broad and well tested against fakes. It is **not** yet a staging candidate either, because AC-CORE-17 (Compose up and CI green) has no evidence on this branch.

## 9. Final recommendation

1. **Authorise pushing** `feature/mvp-production-readiness` (with no change to PRs #1–#6), so CI runs. Then run `docker compose up` on a host with Docker. These two steps close AC-CORE-17 and would make the build a staging candidate.
2. **Supply GitHub App credentials and a test organisation;** run the real repository path.
3. **Decide O-01;** then implement and evaluate one provider adapter (FR-LLM-10).
4. **Decide the production blockers:** O-12/C-13, O-13 ceremony, C-17, O-03, O-15.
5. **Decide the product points:** C-06/C-07 (unblocks T04/T05), O-16, C-09, C-16, O-08, D-R7-10, plus the retention values and the tenant-deletion authorisation policy.
6. **Then:** an independent security review, image and dependency scans, and a backup and restore drill, before any production decision.

## 10. Deliverables

| | Document |
|---|---|
| A | `docs/reports/final-mvp-completion-report.md` (this file) |
| B | `docs/testing/mvp-acceptance-criteria-matrix.md` |
| C | `docs/operations/production-readiness-checklist.md` |
| D | `docs/testing/external-integration-verification.md` |
| E | `docs/security/security-verification-report.md` |
| F | `docs/operations/production-deployment-and-recovery.md` |
| G | `docs/decisions/mvp-final-decision-closure.md` |

Supporting documents:
- `docs/operations/retention-and-deletion.md` (new);
- updated `production-deployment.md`, `recovery-runbook.md`, `secrets-and-key-management.md`;
- `docs/testing/verification-matrix.md`, marked superseded.
