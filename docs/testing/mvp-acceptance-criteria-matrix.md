# MVP Acceptance Criteria Matrix (PRD v0.7, 60 criteria)

| | |
|---|---|
| Branch | `feature/mvp-production-readiness` (local only; never pushed) |
| Evidence date | 2026-11-08 |
| Test environment | PostgreSQL 16; the real worker process; an in-process fake GitHub server; the deterministic fake model provider; ClamAV through a fake clamd for unit tests (real ClamAV only in CI on PR #4) |
| Backend | 263 tests (75 unit in 15 classes, 188 integration in 27 classes), 0 failures, 0 errors, 0 skipped |
| Frontend | 36 Vitest tests (9 files), `tsc`, ESLint and `vite build` all pass |

**Status values**
- **VERIFIED**: an executed automated test supports the whole criterion.
- **VERIFIED (qualified)**: tested, but only with a fake external service, only in part, or with limited scope. The qualifier is stated in the row.
- **IMPLEMENTED, NOT VERIFIED**: code exists, but the required runtime evidence could not be produced here.
- **BLOCKED**: needs credentials, an owner decision or infrastructure this environment lacks.
- **NOT COMPLETE**: part of the criterion is missing from the code.

A fake-service result says nothing about the real service.

## Summary

| Status | Count | Criteria |
|---|---|---|
| VERIFIED | 37 | See rows |
| VERIFIED (qualified) | 18 | AC-CORE-02, 03, 06, 09, 11, 12, 15, 16; AC-DOC-05, 11, 13, 15, 19; AC-EXT-31, 33, 36, 39, 43 |
| IMPLEMENTED, NOT VERIFIED | 1 | AC-CORE-17 |
| BLOCKED | 1 | AC-EXT-37 (O-32) |
| NOT COMPLETE | 3 | AC-EXT-28, AC-EXT-42 (T04 resume, C-06/C-07), AC-EXT-30 (backups expiry, O-07) |

**The MVP is not 100 % complete:**
- 23 criteria are qualified, not verified, blocked or not complete.
- No criterion was removed or reworded.
- Real GitHub and real LLM behaviour has **not** been verified.

Change since the previous matrix (`docs/testing/verification-matrix.md`, 2026-11-07):
- **Now built and verified:** AC-CORE-06, 14, 16; AC-DOC-12, 14; AC-EXT-23, 31 (qualified), 32, 41.
- **Corrected downward:** AC-EXT-28 and AC-EXT-42 were previously VERIFIED or partial without noting that the Business Reviewer resume and close (T04/T05) do not exist. They are now **NOT COMPLETE**.

## AC-CORE

| AC | Status | Evidence | Gap / qualifier |
|---|---|---|---|
| AC-CORE-01 | VERIFIED | `RequirementApiIT`, `DocumentApiIT`, `RequirementRoutingIT`, `AuditIT` | — |
| AC-CORE-02 | VERIFIED (qualified) | `RepositoryIntegrationIT` (7), `GitHubBrokerTest` (7), `LinkRequestIT` (7): staff registration, tenant clash, installation token narrowed to `contents:read`, SHA pinned | **Fake GitHub only.** Real App, test organisation and repository are needed (credentials checklist in deliverable D) |
| AC-CORE-03 | VERIFIED (qualified) | `AnalysisIT.fullPathProducesAVerifiedBrdVersionInInternalReview` | **Fake model only.** Real provider O-01 is open; BRD quality is unmeasured |
| AC-CORE-04 | VERIFIED | `EvidenceVerifierTest` (7), `AnalysisIT` (failed citations become ASSUMPTION or FLAGGED) | — |
| AC-CORE-05 | VERIFIED | `AnalysisIT`, `EvidenceVerifierTest` (sections; `unsupported[]` becomes UNSUPPORTED_REQUIREMENTS) | — |
| AC-CORE-06 | VERIFIED (qualified) | `ClarificationIT.aBlockingAgentQuestionPausesTheAnalysisUntilTheOwnerAnswers`: PAUSED result, T16, answer, T17; the old execution is SUPERSEDED, the new one runs with the answer; audited. `aNonBlockingQuestionDoesNotStopTheRun`; `addingADocumentDuringAnalysisCancelsTheRunAndRestartsWithIt` (STALE) | The pause unit is the whole BRD commit (D-R7-02); repository and document stages are never reset. Fake model |
| AC-CORE-07 | VERIFIED | `ReviewIT` (approve, reject, revision to v2; v1 approval invalid; four-eyes on correction authors) | — |
| AC-CORE-08 | VERIFIED | `ReviewIT` (client 404 before release; RELEASED only) | — |
| AC-CORE-09 | VERIFIED (qualified) | `SchemaSecurityIT`, `RlsIsolationIT`, `RlsPoolLeakIT`, `RequirementVisibilityIT`, `DocumentApiIT`, `LinkRequestIT`, `RepositoryIntegrationIT`, `ReviewIT`, `ClarificationIT`, `RetentionIT` | The integrations channel is covered only against fakes |
| AC-CORE-10 | VERIFIED | `JobQueueIT` (13: lease, fencing, crash recovery), `OutboxDispatcherIT` (7) | — |
| AC-CORE-11 | VERIFIED (qualified) | `ReviewIT`: a second approval gives 409 APPROVAL_CONFLICT; a unique index is the backstop | Sequential, not truly concurrent threads |
| AC-CORE-12 | VERIFIED (qualified) | `ModelGatewayIT.budgetCapStopsCalls` (tenant and requirement caps); `AnalysisIT` maps BUDGET_EXCEEDED to COST_CAP and T19 | Cap **values** are not decided (O-09); defaults are placeholders |
| AC-CORE-13 | VERIFIED | `WorkflowConsistencyIT` (9), `DefinitionVerifierTest`, Tx C checks in `AnalysisIT`; database CHECKs on transitions and actors | — |
| AC-CORE-14 | VERIFIED | `ClarificationIT` (CLARIFICATION_ASSIGNED notification and inbox; other users get 404); badge = unread count in `Layout`; `Clarifications.test.tsx` | Staff have an inbox page but no badge |
| AC-CORE-15 | VERIFIED (qualified) | Security events RESOURCE_NOT_FOUND, LINK_REQUEST_REJECTED, STAFF_ACCESS_DENIED, MFA_FAILED, MALWARE_DETECTED (`DocumentApiIT`, `StaffAuthIT`, `ReviewIT`) | Recorded per attempt; no aggregation of repeats and no alert channel (C-17) |
| AC-CORE-16 | VERIFIED (qualified) | `RetentionIT` (4): per-tenant setting, class-by-class deletion, tenant deletion procedure on a test tenant, `TENANT_DATA_DELETED` audit. Packaged `retention apply` smoke run exited 0 | Durations are PRD development defaults, **not production-approved** (O-02/O-04/O-23/O-25); who may authorise a tenant deletion is an owner decision (D-R7-13) |
| AC-CORE-17 | IMPLEMENTED, NOT VERIFIED | `docker compose config` valid; no test uses a real LLM key (fake provider; CI has no LLM secret) | **`docker compose up` was not run: no Docker daemon here. CI has not run on this branch: push not authorised.** CI was last green on PR #6 |

## AC-DOC

| AC | Status | Evidence | Gap / qualifier |
|---|---|---|---|
| AC-DOC-01 | VERIFIED | `DocumentApiIT` | — |
| AC-DOC-02 | VERIFIED | `DocumentApiIT`, `DocumentContentValidatorTest` | — |
| AC-DOC-03 | VERIFIED | `DocumentContentValidatorTest`, `DocumentApiIT` | — |
| AC-DOC-04 | VERIFIED | `DocumentApiIT` (scanner down means QUARANTINED) | — |
| AC-DOC-05 | VERIFIED (qualified) | `DocumentApiIT` cross-tenant list, download and delete; denied and recorded | **No preview exists** (C-04 open; FR-UPL-11) |
| AC-DOC-06 | VERIFIED | `DocumentApiIT` | — |
| AC-DOC-07 | VERIFIED | `DocumentApiIT`, `AuditIT` | — |
| AC-DOC-08 | VERIFIED | `DocumentApiIT` | — |
| AC-DOC-09 | VERIFIED | `EncryptedFileObjectStoreTest` (7), `SchemaSecurityIT` | Development store only; production store and KMS O-12/C-13 open |
| AC-DOC-10 | VERIFIED | `ExtractionIT` (UNSUPPORTED_CONTENT); guard and exclusion listing (`ClarificationIT`) | — |
| AC-DOC-11 | VERIFIED (qualified) | `AnalysisIT` (citation of the PROCESSED chunk, label stored) | Fake model |
| AC-DOC-12 | VERIFIED | `ClarificationIT.aDocumentNeedingADecisionWaitsForTheReviewerThenAnalysisStarts` (excluded and listed in `brd_version.excluded_documents`) | — |
| AC-DOC-13 | VERIFIED (qualified) | `AnalysisIT` (injected text travels as data; workflow unaffected); `ModelGatewayIT` (tool results are data; out-of-registry tools refused) | Fake model: real-model susceptibility not measured |
| AC-DOC-14 | VERIFIED | `ClarificationIT.addingADocumentAfterAnalysisMakesTheReviewVersionStale`, `...DuringAnalysisCancelsTheRunAndRestartsWithIt`, `AnalysisIT` stale test | — |
| AC-DOC-15 | VERIFIED (qualified) | `ReviewIT` (content and input-set hash binding, STALE_INPUTS), `ClarificationIT` (a document change invalidates approvals) | A repository head change after pinning is not re-checked (the SHA is pinned per input set) |
| AC-DOC-16 | VERIFIED | `ExtractionIT` | — |
| AC-DOC-17 | VERIFIED | `ExtractionIT`, `JobQueueIT` | — |
| AC-DOC-18 | VERIFIED | `RequirementRoutingIT` | — |
| AC-DOC-19 | VERIFIED (qualified) | `ReviewIT` (reviewers see quotes and references; client sees references) | No separate audited "open source file" endpoint |
| AC-DOC-20 | VERIFIED | `ReviewIT` | — |

## AC-EXT

| AC | Status | Evidence | Gap / qualifier |
|---|---|---|---|
| AC-EXT-21 | VERIFIED | Guard 9.4 (`InputSets`), `AnalysisIT` | — |
| AC-EXT-22 | VERIFIED | `DocumentApiIT` | — |
| AC-EXT-23 | VERIFIED | `ClarificationIT` (T07/T10, Business Reviewer EXCLUDE, T12/T13, exclusion listed) | — |
| AC-EXT-24 | VERIFIED | `ExtractionIT` | — |
| AC-EXT-25 | VERIFIED | `ExtractionIT` | — |
| AC-EXT-26 | VERIFIED | `EvidenceVerifierTest`, `AnalysisIT` | — |
| AC-EXT-27 | VERIFIED | `EvidenceVerifierTest`, `AnalysisIT`, `ReviewIT` | — |
| AC-EXT-28 | **NOT COMPLETE** | Routing SUBMITTED to MANUAL_INTAKE or REPO_VALIDATING, and no repository validation, BA or extraction (`RequirementRoutingIT`) | **T04 resume and T05 close by a Business Reviewer are not implemented.** T04 depends on C-06/C-07 (owner decisions). The owner can cancel (T29) |
| AC-EXT-29 | VERIFIED | `ReviewIT` | — |
| AC-EXT-30 | **NOT COMPLETE** | `RetentionIT`: documents, versions, chunks, snapshots, indexes, BRDs and evidence, content-free audit. Workspace sweep in `DocumentMonitor` | **Backup copies are not covered** (FR-RET-06; platform O-07). Production values are not approved |
| AC-EXT-31 | VERIFIED (qualified) | Guard logic (`InputSets`), `ClarificationIT` document-decision path | The exact interleaving (decision while another document is still processing) is not tested separately |
| AC-EXT-32 | VERIFIED | Guard blocks on UPLOAD_FAILED; owner ACKNOWLEDGE_EXCLUSION endpoint (`ClarificationIT`) | — |
| AC-EXT-33 | VERIFIED (qualified) | `DocumentApiIT` (malware never exposed or analysed; MALWARE_DETECTED recorded) | "Admins are alerted": the event is recorded, but there is no alert channel (C-17) |
| AC-EXT-34 | VERIFIED | `DocumentApiIT` | — |
| AC-EXT-35 | VERIFIED | `DocumentApiIT`, `ClamdScannerTest`; real ClamAV in CI (PR #4) | Production scanner O-03 |
| AC-EXT-36 | VERIFIED (qualified) | `SchemaSecurityIT`, `RlsIsolationIT`, `RlsPoolLeakIT`, `JobQueueIT` | Production database platform check (O-31) not run |
| AC-EXT-37 | **BLOCKED** | — | O-32: admin access grants to tenant content are not designed or approved. Staff get no automatic tenant or source access |
| AC-EXT-38 | VERIFIED | `StaffProvisioningIT` (11: two-person grants), `ReviewIT` (submitter is a client identity and cannot approve) | — |
| AC-EXT-39 | VERIFIED (qualified) | `RetentionIT` (defaults 30/90/365/365 configurable, per-tenant override, deletion applies them); 24 h workspace sweep | The values are configurable, not approved for production |
| AC-EXT-40 | VERIFIED | `ReviewIT`, `ClarificationIT` (in-app only; no email code exists) | — |
| AC-EXT-41 | VERIFIED | `AnalysisIT` stale test, `ClarificationIT` cancellation test | — |
| AC-EXT-42 | **NOT COMPLETE** | MANUAL_INTAKE schedules no extraction or BA (`RequirementRoutingIT`) | Resume (T04) is not built, so "after the resume" cannot happen (C-06) |
| AC-EXT-43 | VERIFIED (qualified) | `EvidenceVerifierTest` (path, line range, range hash, quote, document version, chunk) | Support is checked lexically, not semantically (D-R5-07) |
