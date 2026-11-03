# Decision Register

Tracks owner decisions, PRD/TDD conflicts, security findings and implementation decisions.
**PRD v0.7 is the product authority; TDD v1.0.4 is the architecture reference. Neither is modified here.**
Entries are only marked RESOLVED by a recorded owner decision or verified evidence.

Last updated: 2026-11-03 (Milestone 2).

## 1. Owner approvals recorded

| ID | Date | Decision | Scope / limits |
|---|---|---|---|
| CP-0 | 2026-11-02 | Revised pre-M0 plan approved; preparation tasks P-01..P-09 authorised | Planning only |
| GATE-1-W | 2026-11-02 | **Owner waiver of TDD Gate 1 sequencing for Milestone 1 foundation items only** (repo, build, CI, Compose, own Liquibase schema with RLS, tenant-user auth, projects, audit, security events) | O-06, O-11, O-13 remain **OPEN**. Staff roles, MFA, admin bootstrap/provisioning are **not** built |
| D-M1-B | 2026-11-02 | Spring Boot **4.1.2** instead of TDD "Spring Boot 3" (P-06: Spring Framework 6.2, under Boot 3.5, ended OSS support June 2026) | Deviation from TDD section 2 |
| D-M1-C | 2026-11-02 | Root Java package `io.github.tony079.sdlc`; one package per TDD module with `api` / `internal` | |
| D-M1-D | 2026-11-02 | Login uses **organisation (tenant slug) + email + password**. The organisation only selects the tenant for credential lookup; afterwards the tenant comes from the session (FR-AUTH-06) | Provisional, reversible. Relates to C-12 |
| D-M1-E | 2026-11-02 | Development seed (tenants acme/globex, 2 users each) created by the migrate process, only when `SDLC_DEV_SEED=true`, password from the environment | Never in production |
| M2-PLAN | 2026-11-03 | Milestone 2 implementation plan approved, on separate branch `claude/milestone-2-requirements`; PR #1 stays Milestone 1 only | Conditions: syntax-only repository URL validation; ILLEGAL_TRANSITION_ATTEMPTED survives rollback; owner-only visibility; open gates stay visible |
| M2-Q1 | 2026-11-03 | The eight PRD FR-REQ-01 fields. Max lengths: title 200, description 20,000, businessObjective 5,000, expectedOutcomes 10,000, acceptanceCriteria 10,000, repositoryUrl 300, constraints 10,000, integrations 10,000. All required at submission except acceptanceCriteria; "None"/"Not applicable" valid for constraints and integrations. repositoryUrl: required at submission for EXISTING_REPOSITORY, optional in drafts, must be absent for NEW_PRODUCT, stored as unvalidated text (syntax only) | Resolves C-02 by owner decision |
| M2-Q2 | 2026-11-03 | A draft may contain only projectId, kind and title; completeness is validated at submission with field-specific 422 errors | |
| M2-Q4 | 2026-11-03 | In Milestone 2 submission performs only DRAFT → SUBMITTED; routing (T02/T03) uses the transactional outbox in Milestone 3 | No synchronous routing |
| M2-C03 | 2026-11-03 | Human-readable requirement key `REQ-000123`, generated server-side, unique and concurrency-safe; UUID remains the primary key | Resolves C-03 by owner decision. Implemented per tenant (D-M2-01) |
| M2-C06 | 2026-11-03 | The Client Approver provides/attaches the repository link; the Business Reviewer may resume manual intake only after the link is available and validated; no Business Reviewer bypass | Rule recorded. **Not implemented in Milestone 2** (needs the repository milestone and O-06) |

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

### Implementation decisions (Milestone 2)

| ID | Decision | Rationale / limit | Status |
|---|---|---|---|
| D-M2-01 | Requirement keys are per tenant (each tenant has its own REQ-000001…) | A global sequence would let one tenant infer another's volume | ACTIVE |
| D-M2-02 | Workflow state consistency is enforced declaratively in the database: deferred composite FK from `workflow_instance (id, last_seq, projected_state)` to the latest transition, predecessor FK in `workflow_transition`, and a CHECK listing legal transitions | No SECURITY DEFINER functions or triggers. Each milestone extends the CHECK in a reviewed migration | ACTIVE |
| D-M2-03 | One client-visible version (`requirement.version`, ETag / If-Match) guards edits, submit and cancel; missing If-Match → 428 | All three mutate under `WHERE version = :expected` and exclude each other | ACTIVE |
| D-M2-04 | Refused transitions are audited with `recordIndependently` (separate transaction) | Condition 3 of the M2 approval; proven by `AuditIT.illegalTransitionAttemptIsAuditedEvenThoughTheRequestRollsBack` | ACTIVE |
| D-M2-05 | Idempotency records are kept indefinitely | The 24-hour replay window (TDD 20.1) and cleanup belong to the retention milestone | ACTIVE — known limit |
| D-M2-06 | Cancellation reason: 10–500 characters, stored on the transition row (tenant-scoped); never in the audit trail | Audit stays content-free | ACTIVE |
| D-M2-07 | The requirement list reads the workflow projection with a join; only the workflow module writes workflow tables | Reads are allowed by TDD 9.5; ArchUnit cannot inspect SQL, the schema constraints are the backstop | ACTIVE |

### Pre-implementation verification (Milestone 2, scratch PostgreSQL 16.15)

| ID | Check | Result |
|---|---|---|
| V-M2-1 | Per-tenant key generation, 60 concurrent transactions with rollbacks | Distinct, contiguous, rollback consumes no number. Re-proven by `RequirementConcurrencyIT` and `RequirementTransactionIT` |
| V-M2-2 | Optimistic locking under concurrent writers | Second writer updates 0 rows → 409. Re-proven by `RequirementConcurrencyIT` |
| V-M2-3 | Append-only revisions and transitions | UPDATE/DELETE/TRUNCATE denied for `sdlc_app`. Re-proven by `SchemaSecurityIT`, `RequirementRevisionIT`, `WorkflowConsistencyIT` |
| V-M2-4 | projected_state ↔ transition history consistency | Database refuses state change without history, wrong predecessor, illegal pair, branching, leaving CANCELLED. Re-proven by `WorkflowConsistencyIT` |
| V-M2-5 | Idempotent create under concurrency | **Finding:** reading the winner inside the claiming statement uses a stale snapshot and returns nothing. Fixed design: claim, then read in a separate statement. Proven by `RequirementTransactionIT` (commit and rollback races) |

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
| C-02 | Requirement form fields: PRD FR-REQ-01 lists 8 fields; TDD 20.4 has title/description | AO | **RESOLVED by owner decision M2-Q1** (implemented in Milestone 2; TDD text unchanged) |
| C-03 | Human-readable requirement ID (`REQ-000123`) absent in TDD | PO, AO | **RESOLVED by owner decision M2-C03** (implemented in Milestone 2; TDD text unchanged) |
| C-04 | Document preview (FR-UPL-11) absent in TDD API | AO, SO | Upload milestone |
| C-05 | Reviewer-raised clarification (FR-CLR-13) has no TDD endpoint | AO | Clarification milestone |
| C-06 | MANUAL_INTAKE resume/conversion vs who may link a repository | PO, AO | Rule decided (M2-C06); implementation pending the repository milestone and O-06 |
| C-07 | Platform Admin cancel during MANUAL_INTAKE (PRD-internal ambiguity) | PO | **PROPOSED, pending explicit Product Owner confirmation:** Platform Admin may cancel with a mandatory reason and an immutable audit event; nothing deleted. Not built (no Platform Admin accounts until O-06/O-13) |
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
| **C-18** | **Requirement visibility.** PRD section 4: "Client users can only see their own requirements." Decision M2-C06 has the Client Approver attach the repository link, which under the PRD wording they could not do for a Client User's requirement they cannot see. **Security implications:** tenant-wide approver visibility widens who in a tenant can read requirement content (weaker least privilege, larger exposure if an approver account is compromised); owner-only visibility blocks the approver's role in C-06. **Implemented now:** owner-only for both client roles; tenant-wide approver visibility is **not** built | PO | **OPEN** — before the repository milestone |

## 5. Security findings

| ID | Finding | Applies to M1 code? | Status / gate |
|---|---|---|---|
| S-01 | TDD archive: `sys.purge_expired_audit` executable by the app role | No — M1 schema has no purge function; `sdlc_app` has no UPDATE/DELETE on `audit_event` (verified by `SchemaSecurityIT`) | **OPEN** for the archive; must be resolved before any audit-retention implementation |
| S-02 | TDD archive: app role may INSERT/UPDATE platform role assignments | No — M1 has no platform role tables | **OPEN**; must be resolved before staff roles (O-06) / provisioning (O-13) |
| S-04 | Empty tenant setting on pooled connections | M1 helper uses `nullif(..., '')`; **verified** by `RlsPoolLeakIT` (one physical connection, no leak, zero rows without context, no error) | Verified for the M1 schema only; remains OPEN for the archive |
| S-06 | TDD archive: REPO_VALIDATING retry safeguard (S-27) not implemented | No — no queue/retry yet. Milestone 2's own transition CHECK allows only CREATE, T01 and T29, so no retry transition exists in this schema | **OPEN**; must be implemented with the queue/admin-retry milestone |
| S-03, S-05, S-07..S-12 | See planning record | — | OPEN |

## 6. Acceptance-criteria progress

### Milestone 2

No acceptance criterion is claimed complete. Milestone 2 contributes evidence toward: AC-CORE-01 (requirement with ID
and SUBMITTED state, audited; document attachments are a later milestone), FR-REQ-01/03/04/06/07 (notice), FR-AUTH-04/13
(owner-only visibility with security events), FR-WF-01/09 (persisted state with transition history), FR-AUD-01/03.
AC-DOC-18 / AC-EXT-28 (MANUAL_INTAKE routing) wait for the outbox in Milestone 3 (M2-Q4).

### Milestone 1

No acceptance criterion is claimed complete. Milestone 1 contributes evidence toward:
AC-CORE-17 (build, tests without an LLM key; Compose smoke runs in CI), AC-EXT-36 (RLS, non-bypass role,
pooled-connection test; jobs clause pending C-14), AC-CORE-09 (API and database cross-tenant tests for projects),
FR-AUTH-01/03/04/06/13/17/18/20/21, FR-AUD-01/02/05/06 (foundation), FR-MON-01 (failed login, resource miss).
