# Final Completion Baseline (2026-11-07)

Authoritative sources: PRD v0.7 (`docs/product/PRD-v0.7.md`), TDD v1.0.4 (`docs/architecture/TDD-v1.0.4.md`), decision
register (`docs/decisions/decision-register.md`), readiness (`docs/architecture/r1-m4-m6-implementation-readiness.md`),
programme report (`docs/reports/mvp-programme-report-2026-11-04.md`), R1-M4 report
(`docs/reports/r1-m4-implementation-report-2026-11-07.md`).

## 1. Git

| Item | Value |
|---|---|
| Working branch | `feature/r1-m5-m6-completion`, created from `feature/r1-m4-repository-registration` at `92fd9df` |
| Upstream | none (local only, nothing pushed) |
| Working tree | clean at creation |
| Lineage | PR #1 `652fa9a` → #2 `1bc15f7` → #3 `bf4318f` → #4 `ab1ac05` → #5 `7e98a34` → #6 `e3feb84` → R1-M4 (7 local commits) → this branch |
| PR branches | unchanged; local equals remote for all six |

## 2. Test baseline (executed on `92fd9df`'s code, 2026-11-07)

| Suite | Result |
|---|---|
| Backend `mvn verify` (PostgreSQL 16, local cluster) | 232 tests, 0 failures, 0 errors (68 unit, 164 integration) |
| Frontend Vitest / tsc / ESLint / vite build | 30/30; OK; OK; OK |
| gitleaks (full history) | no leaks |
| Docker Compose | `config` valid; **runtime not executed** (no Docker daemon in this environment) |
| CI | last green on PR #6 (`e3feb84`); R1-M4 and later not run (not pushed) |

No build or runtime failure is known at baseline.

## 3. Milestones and status at baseline

| Milestone (PRD/TDD 28.1) | Status |
|---|---|
| R1-M0/M1 foundation (tenancy, RLS, auth, audit, projects) | Implemented, tested (PR #1) |
| R1-M2 requirements, revisions, workflow T01/T29 | Implemented, tested (PR #2) |
| R1-M3 outbox, job queue, routing T02/T03 | Implemented, tested (PR #3) |
| Documents: upload, encrypted store, ClamAV scan | Implemented, tested (PR #4); production store O-12, production scanner O-03 open |
| Document extraction | Implemented, tested (PR #5); gate O-15 default off |
| Model gateway (fake provider) | Implemented, tested (PR #6); real provider O-01 open |
| R1-M4 repository registration, staff identity/MFA, broker, validation T06/T07/T09, snapshot, index | Implemented, tested against a fake GitHub (local branch); real GitHub not verified |
| **R1-M5 agent runtime, BA agent, BRD, evidence checker (T08/T11/T13–T19)** | **Missing** |
| **R1-M6 review, approval, release, client view, clarifications, notifications (T20–T28)** | **Missing** |
| Retention (O-02/O-04/O-23/O-25), admin retry (T26, O-27), O-32 access grants | Missing; gated by open decisions |

## 4. Acceptance criteria (PRD lines 1193–1269) at baseline

| Group | Covered by implemented code | Missing |
|---|---|---|
| AC-CORE-01..17 | 01, 02 (fake GitHub), 09 (base), 10, 13 (engine), 15 (recorder), 17 | 03, 04, 05, 06, 07, 08, 11, 12 (mechanism exists in gateway), 14, 16 |
| AC-DOC-01..20 | 01–09, 16–18 | 10–15, 19, 20 |
| AC-EXT-21..43 | 21–25, 28, 31–36, 42 | 26, 27, 29, 30, 37, 38 (DB check exists for staff grants), 39, 40, 41, 43 |

The full matrix with evidence is maintained in `docs/testing/verification-matrix.md` (created on this branch).

## 5. Open decisions affecting completion

O-01 (model provider), O-03 (production scanner), O-08 (flag acknowledgement, provisional default in TDD 16.7),
O-09 (budget values), O-10 (re-run policy), O-12/C-13 (production store and KMS), O-13 production ceremony,
O-15 (extractor isolation), O-16/O-21/O-22 (M6 product details), O-25 (snapshot retention), O-27/S-06 (retry after
failure), O-32 (admin access to tenant content), O-02/O-04/O-23 (retention), C-05, C-07, C-09, C-16, C-17, D-M1-06,
CP-2, plus the flagged D-M4R-04/10/11/16. None is resolved by this work; provisional TDD defaults are used where the
TDD states one, and every use is recorded.

## 6. External dependencies

| Dependency | State |
|---|---|
| GitHub App + test organisation | not available to this session |
| LLM provider (O-01) | not decided; fake only |
| Production object store / KMS (O-12) | not decided |
| Production scanner (O-03) | ClamAV dev/CI only |
| Docker daemon | not available here |
| RabbitMQ / Redis | **not part of the TDD architecture**: messaging is the PostgreSQL transactional outbox and job queue (TDD 10, 11; decision Q-M3-4). Not added |

## 7. Dependency graph

```
foundation ─> requirements ─> outbox/jobs ─> documents ─> extraction ─┐
                                   │                                    ├─> R1-M5 BA/BRD ─> R1-M6 review/release
              staff identity ─> R1-M4 repository ─────────────────────┘
model gateway ─────────────────────────────────────────────────────────┘
```

## 8. Plan for this branch (priority order)

1. R1-M5: guard 9.4 and T11, frozen input set, BA_ANALYSIS through the gateway (untrusted data blocks, versioned prompt),
   pending result (Tx P), BA_COMMIT_RESULT (Tx C) with evidence verification, BRD versions, T18/T20, T19.
2. R1-M6 core: Business Reviewer capability, review queue, flag acknowledgement, approval T21 bound to hashes,
   T22/T24, T23, release T25 in a separate transaction, client released view without excerpts, T27/T28.
3. Frontend journeys, operations documents, verification matrix, final report.
