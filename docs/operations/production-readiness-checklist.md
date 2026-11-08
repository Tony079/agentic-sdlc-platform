# Production Readiness Checklist (2026-11-08)

Each gate is **PASS** only with executed evidence. **OPEN** means the work, the evidence or the owner decision is missing. Branch: `feature/mvp-production-readiness` (local).

## A. Functional completeness

| # | Gate | Status | Evidence / blocker |
|---|---|---|---|
| A1 | All 60 acceptance criteria verified | **OPEN** | 37 VERIFIED, 18 qualified, 1 not verified, 1 blocked, 3 not complete (`docs/testing/mvp-acceptance-criteria-matrix.md`) |
| A2 | Workflow transitions T01–T29 | **OPEN** | All implemented except T04 (C-06/C-07), T05, T08 (superseded by T11, D-R5-01) and T26 (O-27) |
| A3 | Clarifications, document decisions, staleness | PASS | `ClarificationIT` (7) |
| A4 | Review, approval, release, client view | PASS | `ReviewIT` (4); release mode AUTOMATIC is provisional (O-16) |
| A5 | Retention and tenant deletion | PASS (mechanism) / **OPEN** (values) | `RetentionIT`; values need owner confirmation (O-02/O-04/O-23/O-25) |
| A6 | Version comparison | PASS (minimal) | `ReviewIT`; priority conflict C-16 open |
| A7 | Tool calling in the gateway | PASS (fake provider) | `ModelGatewayIT` |

## B. External integrations

| # | Gate | Status | Evidence / blocker |
|---|---|---|---|
| B1 | Real GitHub App verified | **OPEN** | No credentials (`docs/testing/external-integration-verification.md`) |
| B2 | Real LLM provider adapter and verification | **OPEN** | O-01; no adapter |
| B3 | Production malware scanner | **OPEN** | O-03 |
| B4 | Production object store and KMS | **OPEN** | O-12/C-13 |

## C. Security

| # | Gate | Status | Evidence / blocker |
|---|---|---|---|
| C1 | Tenant isolation tests | PASS | `SchemaSecurityIT`, `RlsIsolationIT`, `RlsPoolLeakIT` |
| C2 | Audit immutability for runtime logins (S-01) | PASS | `RetentionIT` |
| C3 | Two-person grants (S-02) | PASS | `StaffProvisioningIT` |
| C4 | Production administrator ceremony | **OPEN** | O-13 not implemented |
| C5 | Key rotation / KMS | **OPEN** | SEC-REVIEW-2 |
| C6 | Alert channel for security events | **OPEN** | C-17 |
| C7 | Extractor isolation | **OPEN** | O-15 (extraction must stay off for real documents) |
| C8 | Secret scan | PASS | gitleaks, full history clean |
| C9 | Dependency and image scans | **OPEN** | npm production 0; Maven and image scans not run |
| C10 | Independent security review or penetration test | **OPEN** | Not performed |

## D. Build, deployment and operations

| # | Gate | Status | Evidence / blocker |
|---|---|---|---|
| D1 | Backend build and tests | PASS | 263 tests, 0 failures (local PostgreSQL 16) |
| D2 | Frontend build and tests | PASS | Vitest 36/36, `tsc`, ESLint, `vite build` |
| D3 | CI green on the release branch | **OPEN** | Not pushed (no authorisation) |
| D4 | `docker compose up` smoke | **OPEN** | No Docker daemon here; only `docker compose config` was validated |
| D5 | One-shot processes (migrate, provision, retention) | PASS (retention smoke) / partial | `retention apply` jar smoke exited 0; migrate is exercised by every test run; provision by `StaffProvisioningIT` |
| D6 | Backup and restore drill | **OPEN** | Procedure documented, never exercised |
| D7 | RPO/RTO targets | **OPEN** | Owner decision; not invented |
| D8 | Monitoring and alerting stack | **OPEN** | Health, structured logs and Micrometer metrics exist; no alerting stack (C-17) |
| D9 | Hosting platform and production database platform check | **OPEN** | O-07, O-31 |
| D10 | Runbooks | PASS | `docs/operations/*` (8 documents, updated 2026-11-08) |

## E. Governance

| # | Gate | Status |
|---|---|---|
| E1 | Owner decisions required for production closed | **OPEN** (`docs/decisions/mvp-final-decision-closure.md`) |
| E2 | Push, merge and deploy authorisation | Not given. Nothing pushed, merged or deployed |

**Result: 20 of the 33 gates above are OPEN (A5 counted as open for its values).** The platform is not ready for production.
