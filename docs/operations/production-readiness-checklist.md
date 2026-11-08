# Production Readiness Checklist (Deliverable F, 2026-11-08)

Branch `feature/mvp-production-readiness` (local), HEAD after `98a8c8d`.

**Status values (as required by the owner):**
- **PASS**: executed evidence exists.
- **FAIL**: checked, and the requirement is not met.
- **BLOCKED**: cannot be done without a decision, credential or infrastructure not available.
- **NOT VERIFIED**: the work may be done, but no evidence was produced.

## A. Functional completeness

| # | Gate | Status | Evidence / reason |
|---|---|---|---|
| A1 | All 60 acceptance criteria verified | **FAIL** | 39 VERIFIED, 18 with qualification, 1 not verified (AC-CORE-17), 2 blocked (AC-EXT-30, AC-EXT-37) |
| A2 | Workflow transitions T01–T29 | PASS | 28 of 29 implemented, including T04, T05 and T26; T08 superseded by T11 (D-R5-01). `WorkflowConsistencyIT`, `DefinitionVerifier` |
| A3 | Manual intake resume and close (C-06/C-07) | PASS | `ManualIntakeIT` (3) |
| A4 | Review, manual release with separation of duties (O-16) | PASS | `ReviewIT` (5) |
| A5 | Change requests and scope diff (C-09, C-16) | PASS | `ReviewIT`, `ScopeDiffTest` |
| A6 | Retention, closure, tenant soft deletion (O-25) | PASS | `RetentionIT` (5), `TenantLifecycleIT` (2), jar smoke |
| A7 | Bounded and operator retry (O-27) | PASS | `JobQueueIT` (14), `OperatorRetryIT` (2) |
| A8 | Feature-flag acknowledgement (O-08) | PASS | `OperationsIT` |
| A9 | Frontend for the new workflows | PASS (unit) / NOT VERIFIED (browser) | Vitest 40/40, `tsc`, ESLint, build; not exercised in a browser against a running stack this phase |
| A10 | Released requirements reach closure (completion state) | **BLOCKED** | No completion state in Release 1; PO decision (Deliverable G) |

## B. External integrations

| # | Gate | Status | Evidence / reason |
|---|---|---|---|
| B1 | Real GitHub App verified | **BLOCKED** | No App credentials or test organisation |
| B2 | Real LLM provider verified and evaluated (O-01) | **BLOCKED** | Adapters exist and are mock-tested; no API keys, so the evaluation reported NOT_EXECUTED |
| B3 | Managed KMS (O-12/C-13) | **FAIL** | Only `LocalKeyManagement` exists; production mode refuses to start (jar smoke). The adapter needs the hosting platform (O-07) |
| B4 | Production object store | **FAIL** | Only the encrypted local file store; no S3-compatible adapter (O-07) |
| B5 | Dependency and SAST scanners (O-03) | **BLOCKED** | No product named; the kinds report UNAVAILABLE |
| B6 | Alert delivery to real channels (C-17) | **NOT VERIFIED** | Mechanism tested against loopback webhook and SMTP; no real receiver |
| B7 | Production malware scanner | **NOT VERIFIED** | ClamAV in development and CI only; hardening not done |

## C. Security

| # | Gate | Status | Evidence / reason |
|---|---|---|---|
| C1 | Tenant isolation | PASS | `SchemaSecurityIT`, `RlsIsolationIT`, `RlsPoolLeakIT` |
| C2 | Audit immutability; retention-only audit purge (S-01) | PASS | `RetentionIT` |
| C3 | Two-person grants (S-02) | PASS | `StaffProvisioningIT` |
| C4 | Production administrator ceremony (O-13) | PASS (mechanism) | `BootstrapCeremonyIT`; jar refusal while disabled. Never run in a real environment |
| C5 | Key rotation and KMS | **FAIL** | No managed KMS; no re-wrap command (R-01, R-05) |
| C6 | Security alerting (C-17) | PASS (mechanism) / NOT VERIFIED (real channel) | `SecurityAlertingIT` |
| C7 | Extractor isolation (O-15) | PASS (namespace and limits) | `ExtractorIsolationTest` executed; residual R-02 (no mount namespace or seccomp); production host NOT VERIFIED |
| C8 | Secret scan of the repository | PASS | gitleaks, full history, no leaks |
| C9 | Dependency and image vulnerability scans | **NOT VERIFIED** | npm production 0; Maven and image scans not run (offline) |
| C10 | Independent security review or penetration test | **NOT VERIFIED** | Not performed |
| C11 | Production configuration fails closed | PASS | `ProductionConfigurationValidatorTest`; jar smoke refused `STORAGE_KMS_LOCAL`, `SECRETS_PROVIDER_NOT_MANAGED`, `NO_SECURITY_ALERT_CHANNEL` |
| C12 | Admin access grants to tenant content (O-32) | **BLOCKED** | Owner decision; AC-EXT-37 |
| C13 | Legal hold | **BLOCKED** | Not supported; legal decision needed |

## D. Build, deployment and operations

| # | Gate | Status | Evidence / reason |
|---|---|---|---|
| D1 | Backend build and tests | PASS | 296 tests, 0 failures (PostgreSQL 16), plus `ManualIntakeIT` 3/3 after `98a8c8d` |
| D2 | Frontend build and tests | PASS | Vitest 40/40, `tsc`, ESLint, `vite build` |
| D3 | CI green on this branch | **BLOCKED** | Not pushed; no authorisation |
| D4 | `docker compose up` smoke | **BLOCKED** | No Docker daemon here; `compose config` is valid |
| D5 | One-shot processes from the packaged jar | PASS | `migrate` exit 0 (104 changesets); `retention apply` exit 0; removed and disabled commands refused |
| D6 | Backup and restore drill | **NOT VERIFIED** | Procedure documented, never exercised |
| D7 | RPO/RTO targets | **BLOCKED** | Owner decision; not invented |
| D8 | Log retention 90 days (O-25) | **NOT VERIFIED** | A log-platform setting; no platform chosen |
| D9 | Hosting platform and production database platform check (O-07, O-31) | **BLOCKED** | Owner and operations decision |
| D10 | Runbooks | PASS | `docs/operations/*`, updated 2026-11-08 (retention, alerting, scanning, extraction isolation, release and retry, secrets, deployment) |

## E. Governance

| # | Gate | Status |
|---|---|---|
| E1 | Owner decisions required for production closed | **BLOCKED** (Deliverable G: O-07, O-32, O-03 products, provider selection, completion state, RPO/RTO, legal hold) |
| E2 | Push, merge and deploy authorisation | **BLOCKED**: not given. Nothing pushed, merged or deployed |

## Result

| Status | Count |
|---|---|
| PASS (including PASS-mechanism rows) | 21 |
| FAIL | 4 (A1, B3, B4, C5) |
| BLOCKED | 12 (A10, B1, B2, B5, C12, C13, D3, D4, D7, D9, E1, E2) |
| NOT VERIFIED | 6 (B6, B7, C9, C10, D6, D8) |
| **Total** | **43** |

Rows marked "PASS / NOT VERIFIED" (A9, C6) are counted as PASS above, and their NOT VERIFIED part is named in the row.

**The platform is not ready for production.**
