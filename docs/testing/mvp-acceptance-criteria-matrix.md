# MVP Acceptance Criteria Matrix (Deliverable C; PRD v0.7, 60 criteria)

| | |
|---|---|
| Branch | `feature/mvp-production-readiness`, local only, never pushed. HEAD includes `98a8c8d` |
| Evidence date | 2026-11-08 (after the owner-decision implementation) |
| Test environment | PostgreSQL 16 local cluster; the real worker beans; in-process fake GitHub; deterministic fake model provider; fake clamd; extraction inside the required network-namespace sandbox |
| Backend | Full suite on HEAD `df3743d`: **296 tests, 0 failures, 0 errors, 0 skipped** (92 unit, 204 integration). `ManualIntakeIT` re-run after `98a8c8d`: 3/3 |
| Frontend | Vitest 40/40 (10 files); `tsc`, ESLint and `vite build` pass |

**Status values (as required by the owner's prompt)**
- **VERIFIED**: an executed automated test supports the whole criterion.
- **VERIFIED WITH QUALIFICATION**: tested, but only with a fake external service, only in part, or with a stated limit.
- **IMPLEMENTED NOT VERIFIED**: code exists; the required runtime evidence could not be produced here.
- **BLOCKED**: needs a decision, credentials or infrastructure not available.
- **NOT COMPLETE**: part of the criterion is missing from the code.

A fake-service result proves nothing about the real service.

## Summary

| Status | Count | Criteria |
|---|---|---|
| VERIFIED | **39** | See rows |
| VERIFIED WITH QUALIFICATION | **18** | AC-CORE-02, 03, 06, 09, 11, 12, 15, 16; AC-DOC-05, 11, 13, 15, 19; AC-EXT-31, 33, 36, 39, 43 |
| IMPLEMENTED NOT VERIFIED | **1** | AC-CORE-17 |
| BLOCKED | **2** | AC-EXT-30 (backup expiry, O-07), AC-EXT-37 (O-32) |
| NOT COMPLETE | **0** | — |

**Changes since the previous matrix (same date, before the owner decisions):**

| Criterion | Before | Now | Reason |
|---|---|---|---|
| AC-EXT-28 | NOT COMPLETE | VERIFIED | T04 and T05 built (C-06/C-07); `ManualIntakeIT` |
| AC-EXT-42 | NOT COMPLETE | VERIFIED | Extraction is held in MANUAL_INTAKE and scheduled after resume; `ManualIntakeIT` (assertion added in `98a8c8d`) |
| AC-EXT-30 | NOT COMPLETE | BLOCKED | Everything the code controls is covered; the only gap is backup expiry, which depends on the hosting platform (O-07). AC-EXT-30 is not resolved with an invented backup policy |
| AC-CORE-16 | qualified | qualified (different reason) | Tenant deletion is now the approved two-person soft deletion (O-25); retention durations are owner-approved |
| AC-EXT-33 | qualified | qualified (different reason) | Admin alerting now exists (C-17); only real channel delivery is unverified |
| AC-CORE-08 | VERIFIED | VERIFIED | Still holds under manual release (O-16) |

No criterion was removed or reworded. Real GitHub and real LLM behaviour remain **unverified**.

## AC-CORE

| AC | Status | Evidence | Qualification / gap |
|---|---|---|---|
| AC-CORE-01 | VERIFIED | `RequirementApiIT`, `DocumentApiIT`, `RequirementRoutingIT`, `AuditIT` | — |
| AC-CORE-02 | VERIFIED WITH QUALIFICATION | `RepositoryIntegrationIT` (7), `GitHubBrokerTest` (7), `LinkRequestIT` (7) | **Fake GitHub only.** Needs a real App and test organisation (Deliverable E) |
| AC-CORE-03 | VERIFIED WITH QUALIFICATION | `AnalysisIT.fullPathProducesAVerifiedBrdVersionInInternalReview` | **Fake model only.** Adapters exist (O-01) but no real call was made; BRD quality unmeasured |
| AC-CORE-04 | VERIFIED | `EvidenceVerifierTest` (7), `AnalysisIT` | — |
| AC-CORE-05 | VERIFIED | `AnalysisIT`, `EvidenceVerifierTest` | — |
| AC-CORE-06 | VERIFIED WITH QUALIFICATION | `ClarificationIT` (7) | The pause unit is the whole BRD commit (D-R7-02); fake model |
| AC-CORE-07 | VERIFIED | `ReviewIT` | — |
| AC-CORE-08 | VERIFIED | `ReviewIT`: the client gets 404 before release; release now needs a separate `RELEASE_APPROVE` decision bound to the content hash and commit SHA, and passing gates | — |
| AC-CORE-09 | VERIFIED WITH QUALIFICATION | `SchemaSecurityIT` (9), `RlsIsolationIT`, `RlsPoolLeakIT`, `RequirementVisibilityIT`, `DocumentApiIT`, `LinkRequestIT`, `RepositoryIntegrationIT`, `ReviewIT`, `ClarificationIT`, `RetentionIT`, `TenantLifecycleIT`, `OperatorRetryIT` | Integration channels tested only against fakes |
| AC-CORE-10 | VERIFIED | `JobQueueIT` (14), `OutboxDispatcherIT` (7) | — |
| AC-CORE-11 | VERIFIED WITH QUALIFICATION | `ReviewIT` (second approval gets 409; unique index backstop) | Sequential, not truly concurrent threads |
| AC-CORE-12 | VERIFIED WITH QUALIFICATION | `ModelGatewayIT.budgetCapStopsCalls`, `AnalysisIT` | Cap values are placeholders (O-09) |
| AC-CORE-13 | VERIFIED | `WorkflowConsistencyIT` (9), `DefinitionVerifierTest`, database CHECKs (now including T04, T05, T26) | — |
| AC-CORE-14 | VERIFIED | `ClarificationIT`, `Clarifications.test.tsx` | — |
| AC-CORE-15 | VERIFIED WITH QUALIFICATION | Events recorded for cross-tenant and unauthorised approval attempts (`DocumentApiIT`, `StaffAuthIT`, `ReviewIT`). Repeated attempts are now aggregated by threshold rules (`CROSS_TENANT_PROBING`, `REPEATED_STAFF_DENIALS`, `REPEATED_MFA_FAILURES`) and alerted (`SecurityAlertingIT`) | The threshold mechanism is tested with the MFA rule; the cross-tenant rule uses the same mechanism but has no test of its own. Real channel delivery unverified |
| AC-CORE-16 | VERIFIED WITH QUALIFICATION | `RetentionIT` (5: per-tenant setting, closure-based deletion, audit without content); `TenantLifecycleIT` (2: two-person soft deletion, sessions revoked, jobs stopped, data kept, security events and audit; single-person and direct status changes refused by the database). Jar smoke `retention apply` exited 0 | "Deletion" is the **soft** deletion approved in O-25: nothing is physically removed |
| AC-CORE-17 | IMPLEMENTED NOT VERIFIED | `docker compose config` valid (with placeholder secrets, 2026-11-08); packaged jar smoke of `migrate`, `retention` and `provision`; no test uses a real LLM key | **`docker compose up` not run (no Docker daemon). CI not run on this branch (push not authorised)** |

## AC-DOC

| AC | Status | Evidence | Qualification / gap |
|---|---|---|---|
| AC-DOC-01 | VERIFIED | `DocumentApiIT` | — |
| AC-DOC-02 | VERIFIED | `DocumentApiIT`, `DocumentContentValidatorTest` | — |
| AC-DOC-03 | VERIFIED | `DocumentContentValidatorTest`, `DocumentApiIT` | — |
| AC-DOC-04 | VERIFIED | `DocumentApiIT` | — |
| AC-DOC-05 | VERIFIED WITH QUALIFICATION | `DocumentApiIT` (cross-tenant list, download, delete denied and recorded); object reads now also check the tenant in the store | **No preview exists** (C-04 open) |
| AC-DOC-06 | VERIFIED | `DocumentApiIT` | — |
| AC-DOC-07 | VERIFIED | `DocumentApiIT`, `AuditIT` | — |
| AC-DOC-08 | VERIFIED | `DocumentApiIT` | — |
| AC-DOC-09 | VERIFIED | `EncryptedFileObjectStoreTest` (7), `EnvelopeEncryptionTest` (4), `SchemaSecurityIT` | The criterion is met by the development store; the production store and managed KMS are a production gate (Deliverable F), not part of this criterion |
| AC-DOC-10 | VERIFIED | `ExtractionIT`, `ClarificationIT` | — |
| AC-DOC-11 | VERIFIED WITH QUALIFICATION | `AnalysisIT` | Fake model |
| AC-DOC-12 | VERIFIED | `ClarificationIT` | — |
| AC-DOC-13 | VERIFIED WITH QUALIFICATION | `AnalysisIT`, `ModelGatewayIT` | Fake model; real-model susceptibility not measured |
| AC-DOC-14 | VERIFIED | `ClarificationIT`, `AnalysisIT` | — |
| AC-DOC-15 | VERIFIED WITH QUALIFICATION | `ReviewIT`, `ClarificationIT` | Repository head changes after pinning are not re-checked (by design: SHA pinned per input set; release binds the commit SHA) |
| AC-DOC-16 | VERIFIED | `ExtractionIT` (now inside the required sandbox) | — |
| AC-DOC-17 | VERIFIED | `ExtractionIT`, `JobQueueIT` | — |
| AC-DOC-18 | VERIFIED | `RequirementRoutingIT`, `ManualIntakeIT` | — |
| AC-DOC-19 | VERIFIED WITH QUALIFICATION | `ReviewIT` | No separate audited "open source file" endpoint |
| AC-DOC-20 | VERIFIED | `ReviewIT` | — |

## AC-EXT

| AC | Status | Evidence | Qualification / gap |
|---|---|---|---|
| AC-EXT-21 | VERIFIED | `InputSets` guard, `AnalysisIT` | — |
| AC-EXT-22 | VERIFIED | `DocumentApiIT` | — |
| AC-EXT-23 | VERIFIED | `ClarificationIT` | — |
| AC-EXT-24 | VERIFIED | `ExtractionIT` | — |
| AC-EXT-25 | VERIFIED | `ExtractionIT` | — |
| AC-EXT-26 | VERIFIED | `EvidenceVerifierTest`, `AnalysisIT` | — |
| AC-EXT-27 | VERIFIED | `EvidenceVerifierTest`, `AnalysisIT`, `ReviewIT` | — |
| AC-EXT-28 | **VERIFIED** | `RequirementRoutingIT` (routing; no validation, BA or extraction); `ManualIntakeIT` (3): queue for reviewers; resume refused without an authorised link, with a stale revision, a short reason, for client users and for staff without `BUSINESS_REVIEWER`; resume creates revision 2 (`EXISTING_REPOSITORY`), moves to REPO_VALIDATING, is audited and schedules validation once; close requires a reason, moves to CANCELLED, notifies the owner in-app, is audited; no reopening | — |
| AC-EXT-29 | VERIFIED | `ReviewIT` | — |
| AC-EXT-30 | **BLOCKED** | `RetentionIT`: documents, versions, extracted text and chunks, snapshots and indexes, BRDs and evidence; audit without content. 24 h workspace sweep (`DocumentMonitor`) | **Backup copies** (FR-RET-06) depend on the hosting platform's backup rotation (O-07). Also: under O-25, released (SHARED_WITH_CLIENT) requirements are never closed, so their data is not yet subject to deletion |
| AC-EXT-31 | VERIFIED WITH QUALIFICATION | `InputSets`, `ClarificationIT` | The exact interleaving is not tested separately |
| AC-EXT-32 | VERIFIED | `ClarificationIT` | — |
| AC-EXT-33 | VERIFIED WITH QUALIFICATION | `DocumentApiIT` (never exposed or analysed; MALWARE_DETECTED recorded); rule `MALWARE_DETECTED` (HIGH) delivers an operator alert; delivery, dedup and retry tested in `SecurityAlertingIT` against a local webhook and SMTP listener | No real mail server or webhook receiver used |
| AC-EXT-34 | VERIFIED | `DocumentApiIT` | — |
| AC-EXT-35 | VERIFIED | `DocumentApiIT`, `ClamdScannerTest` | Production malware scanner hardening is a production gate |
| AC-EXT-36 | VERIFIED WITH QUALIFICATION | `SchemaSecurityIT`, `RlsIsolationIT`, `RlsPoolLeakIT`, `JobQueueIT` | Production database platform check (O-31) not run |
| AC-EXT-37 | **BLOCKED** | — | **O-32** (admin access grants to tenant content) is not decided. Not resolved with an invented policy. Staff get no automatic tenant or source access |
| AC-EXT-38 | VERIFIED | `StaffProvisioningIT` (11), `BootstrapCeremonyIT` (2), `ReviewIT` (release SoD), `TenantLifecycleIT` (two-person), `OperationsIT` (other-operator acknowledgement) | — |
| AC-EXT-39 | VERIFIED WITH QUALIFICATION | `RetentionIT` (defaults configurable by environment, per-tenant overrides, deletion applies them); 24 h workspace sweep | The PRD lists development defaults 30/90/365/365 and requires owner confirmation (FR-RET-01). Owner decision O-25 set **document 365** and **extracted text 30** days (PRD development default 90), and 90 days for logs (log platform). The values are configurable, so the PRD defaults can be restored without code |
| AC-EXT-40 | VERIFIED | `ReviewIT`, `ClarificationIT`, `ManualIntakeIT` (in-app notifications only) | Email and webhook exist only for **operator security alerts** (C-17); no user notification leaves the platform |
| AC-EXT-41 | VERIFIED | `AnalysisIT`, `ClarificationIT` | — |
| AC-EXT-42 | **VERIFIED** | `ManualIntakeIT.reviewerResumesAManualIntakeRequirementOnceARepositoryIsAuthorised`: a document uploaded in MANUAL_INTAKE reaches READY_FOR_EXTRACTION with no extraction job; after resume exactly one DOCUMENT_EXTRACT job exists | — |
| AC-EXT-43 | VERIFIED WITH QUALIFICATION | `EvidenceVerifierTest` | Lexical support check (D-R5-07) |
