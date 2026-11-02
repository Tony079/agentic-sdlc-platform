# Decision Register

Tracks owner decisions, PRD/TDD conflicts, security findings and implementation decisions.
**PRD v0.7 is the product authority; TDD v1.0.4 is the architecture reference. Neither is modified here.**
Entries are only marked RESOLVED by a recorded owner decision or verified evidence.

Last updated: 2026-11-02 (Milestone 1).

## 1. Owner approvals recorded

| ID | Date | Decision | Scope / limits |
|---|---|---|---|
| CP-0 | 2026-11-02 | Revised pre-M0 plan approved; preparation tasks P-01..P-09 authorised | Planning only |
| GATE-1-W | 2026-11-02 | **Owner waiver of TDD Gate 1 sequencing for Milestone 1 foundation items only** (repo, build, CI, Compose, own Liquibase schema with RLS, tenant-user auth, projects, audit, security events) | O-06, O-11, O-13 remain **OPEN**. Staff roles, MFA, admin bootstrap/provisioning are **not** built |
| D-M1-B | 2026-11-02 | Spring Boot **4.1.2** instead of TDD "Spring Boot 3" (P-06: Spring Framework 6.2, under Boot 3.5, ended OSS support June 2026) | Deviation from TDD section 2 |
| D-M1-C | 2026-11-02 | Root Java package `io.github.tony079.sdlc`; one package per TDD module with `api` / `internal` | |
| D-M1-D | 2026-11-02 | Login uses **organisation (tenant slug) + email + password**. The organisation only selects the tenant for credential lookup; afterwards the tenant comes from the session (FR-AUTH-06) | Provisional, reversible. Relates to C-12 |
| D-M1-E | 2026-11-02 | Development seed (tenants acme/globex, 2 users each) created by the migrate process, only when `SDLC_DEV_SEED=true`, password from the environment | Never in production |

## 2. Implementation decisions (Milestone 1)

| ID | Decision | Rationale / limit | Status |
|---|---|---|---|
| D-M1-01 | Own minimal Liquibase schema; the TDD SQL evidence archive is **not imported** | Archive has open findings S-01, S-02, S-06 and an unresolved baseline (CP-2) | ACTIVE |
| D-M1-02 | Login throttle is in-memory (per account key and per client address) | Resets on restart; not shared across instances. Replace before multi-instance deployment | ACTIVE — known limit |
| D-M1-03 | Every by-id miss on a tenant resource is recorded as `RESOURCE_NOT_FOUND` security event | Under RLS the app cannot distinguish "missing" from "another tenant's" without privileged access; recording all misses satisfies FR-AUTH-04/13 without a privileged lookup | ACTIVE |
| D-M1-04 | Unknown JSON fields are rejected (422), so a client-supplied `tenantId` is refused rather than ignored | TDD 20.1 | ACTIVE |
| D-M1-05 | Migrations run in a separate one-shot `migrate` process as `sdlc_migrator` acting as `sdlc_owner` via startup option `role=sdlc_owner`; the API has Liquibase disabled and no migration credentials | Verified on PostgreSQL 16.15 (spike + `SchemaSecurityIT.runtimeRoleCannotAssumeOwnerRole`) | ACTIVE |
| D-M1-06 | Liquibase 5.0.4 (managed by Boot 4.1.2); usage analytics disabled (`liquibase.analytics.enabled=false`) | Owner to confirm Liquibase 5 licence terms are acceptable | OPEN — owner check |
| D-M1-07 | Single tenant role per user (`CLIENT_USER` or `CLIENT_APPROVER`) | PRD does not require multiple roles per user in M1 | ACTIVE |
| D-M1-08 | GitHub Actions referenced by major version tag, container images by tag | SHA/digest pinning to follow; Dependabot enabled | OPEN — hardening |

## 3. Open owner decisions (from TDD v1.0.4 section 32; statuses unchanged)

| ID | Decision | Blocks | Status |
|---|---|---|---|
| O-06 | Internal staff access model (platform users, assignments, active tenant) | Business Reviewer / Auditor / Platform Admin login → review, clarification and approval milestones | **OPEN** |
| O-11 | MFA for internal staff | Staff login | **OPEN** |
| O-13 | Provisioning flow and two-admin bootstrap | Admin bootstrap, tenant/user provisioning APIs | **OPEN** |
| O-15 | Extractor isolation spike (M3S) | Processing any real document | **OPEN — BLOCKING** |
| O-19 / O-31 | Privileged-function procedure and privilege model on the production database platform (needs O-07) | Production | **OPEN** |
| O-32 | Platform-admin access-grant policy (AC-EXT-37) | AC-EXT-37 sign-off; any admin read of tenant content | **OPEN — BLOCKING** |
| O-01 | LLM provider and model | Real client onboarding (mock/fake provider used until then) | OPEN |
| O-02, O-03, O-23 | Retention confirmation, production scanner, legal hold | Onboarding | OPEN |
| O-04, O-05, O-08, O-09, O-10, O-12, O-16, O-21, O-22, O-25, O-27 | Milestone-level product details (see TDD 32.5) | Their milestones | OPEN |
| O-07, O-18, O-28, O-29, O-30 | Production platform, second named admin, RPO/RTO, retry limits, on-call owners | Production | OPEN |
| O-33, O-34 | Evaluation plan; cost/performance/accessibility measurement | Evaluations, S9 pass line | OPEN |
| CP-2 | SQL archive baseline selection (newer 9-file set never supplied; original archive matches TDD 34.0 for all SQL) | Any use of the archive | **OPEN — BLOCKED** |

## 4. PRD/TDD conflicts (unresolved; PRD remains authority)

| ID | Summary | Decision owner | Needed before |
|---|---|---|---|
| C-01 | Repository registrant: PRD "admin" vs TDD Client Approver (TDD also self-inconsistent) | PO (O-05), SO | Repository milestone |
| C-02 | Requirement form fields: PRD FR-REQ-01 lists 8 fields; TDD 20.4 has title/description | AO | Requirements milestone |
| C-03 | Human-readable requirement ID (`REQ-000123`) absent in TDD | PO, AO | Requirements milestone |
| C-04 | Document preview (FR-UPL-11) absent in TDD API | AO, SO | Upload milestone |
| C-05 | Reviewer-raised clarification (FR-CLR-13) has no TDD endpoint | AO | Clarification milestone |
| C-06 | MANUAL_INTAKE resume/conversion vs who may link a repository | PO, AO | Requirements milestone |
| C-07 | Platform Admin cancel during MANUAL_INTAKE (PRD-internal ambiguity) | PO | Requirements milestone |
| C-08 | Audit purge executable by app role in TDD SQL (see S-01) | SO | Retention milestone |
| C-09 | Client comment / which client role requests changes | PO | Client-view milestone |
| C-10 | TDD 9.7 cites PRD "6B" for document states (should be 6C) | TDD author | Editorial |
| C-11 | TDD 3.1 22-step lifecycle vs PRD ten stages | TDD author | Editorial |
| C-12 | Sign-in identifier: PRD "email and password" vs TDD `loginName`; M1 adds organisation (D-M1-D) | AO, PO | Before production |
| C-13 | Encryption at rest of the object store unspecified | SO, AO | Upload milestone |
| C-14 | AC-EXT-36 jobs clause cannot be verified until the job queue exists | PO | Queue milestone |
| C-15 | O-31 platform check "in M0" needs a target platform (O-07) | OO, AO | Production |
| C-16 | Version diff P0 (FR-UI-02) vs P1 (FR-DOC-06) | PO (O-22) | Review milestone |
| C-17 | P0 admin alerts (AC-EXT-33) vs P1 in-app alerts (FR-MON-02) | PO, SO | Upload milestone |

## 5. Security findings

| ID | Finding | Applies to M1 code? | Status / gate |
|---|---|---|---|
| S-01 | TDD archive: `sys.purge_expired_audit` executable by the app role | No — M1 schema has no purge function; `sdlc_app` has no UPDATE/DELETE on `audit_event` (verified by `SchemaSecurityIT`) | **OPEN** for the archive; must be resolved before any audit-retention implementation |
| S-02 | TDD archive: app role may INSERT/UPDATE platform role assignments | No — M1 has no platform role tables | **OPEN**; must be resolved before staff roles (O-06) / provisioning (O-13) |
| S-04 | Empty tenant setting on pooled connections | M1 helper uses `nullif(..., '')`; **verified** by `RlsPoolLeakIT` (one physical connection, no leak, zero rows without context, no error) | Verified for the M1 schema only; remains OPEN for the archive |
| S-06 | TDD archive: REPO_VALIDATING retry safeguard (S-27) not implemented | No — no queue/retry in M1 | **OPEN**; must be implemented with the queue/admin-retry milestone |
| S-03, S-05, S-07..S-12 | See planning record | — | OPEN |

## 6. Acceptance-criteria progress (Milestone 1)

No acceptance criterion is claimed complete. Milestone 1 contributes evidence toward:
AC-CORE-17 (build, tests without an LLM key; Compose smoke runs in CI), AC-EXT-36 (RLS, non-bypass role,
pooled-connection test; jobs clause pending C-14), AC-CORE-09 (API and database cross-tenant tests for projects),
FR-AUTH-01/03/04/06/13/17/18/20/21, FR-AUD-01/02/05/06 (foundation), FR-MON-01 (failed login, resource miss).
