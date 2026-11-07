# Verification Matrix (PRD v0.7 acceptance criteria)

Branch `feature/r1-m5-m6-completion` (local). Evidence = automated tests executed on 2026-11-07 against PostgreSQL 16,
the real worker process, an in-process fake GitHub server and the deterministic fake model provider.

**Status values**
- **VERIFIED**: supported by an executed test (named below).
- **IMPLEMENTED, NOT VERIFIED**: code exists, but the required runtime or external verification is unavailable.
- **BLOCKED**: needs credentials, an owner decision, infrastructure or an external dependency.
- **NOT IMPLEMENTED**: the work is missing on this branch (a gap, not a blocker).

"Fake" means the verification used a fake external service; it says nothing about the real one.

## AC-CORE

| AC | Status | Evidence / gap |
|---|---|---|
| AC-CORE-01 | VERIFIED | `RequirementApiIT`, `DocumentApiIT`, `RequirementRoutingIT` (create, attach, ID, SUBMITTED, audit) |
| AC-CORE-02 | VERIFIED (fake GitHub) / BLOCKED (real) | `RepositoryIntegrationIT`, `GitHubBrokerTest`, `LinkRequestIT`. A real GitHub App and test organisation are needed |
| AC-CORE-03 | VERIFIED (fake model) / BLOCKED (real quality, O-01) | `AnalysisIT.fullPathProducesAVerifiedBrdVersionInInternalReview`: Phase A and B produce BRD v1 |
| AC-CORE-04 | VERIFIED | `EvidenceVerifierTest`, `AnalysisIT` (failed citations become ASSUMPTION) |
| AC-CORE-05 | VERIFIED | Sections FACTS/ASSUMPTIONS/RISKS/IMPACTED_COMPONENTS/MISSING_REQUIREMENTS; `unsupported[]` becomes UNSUPPORTED_REQUIREMENTS (`AnalysisIT`, `EvidenceVerifierTest`) |
| AC-CORE-06 | NOT IMPLEMENTED | Clarification lifecycle (TDD 17, T16/T17) not built; C-05 open |
| AC-CORE-07 | VERIFIED | `ReviewIT`: approve, reject and revision (v2); v1 approval invalidated on a new version; the submitter is a client user and cannot hold the reviewer capability |
| AC-CORE-08 | VERIFIED | `ReviewIT`: client 404 before release; RELEASED only |
| AC-CORE-09 | VERIFIED (scope tested) | RLS (`SchemaSecurityIT`), `RequirementVisibilityIT`, `DocumentApiIT`, `LinkRequestIT` cross-tenant, `RepositoryIntegrationIT` clash, `ReviewIT` Globex reviewer. Not every integration channel is covered |
| AC-CORE-10 | VERIFIED | `JobQueueIT` (lease, fencing, crash recovery), `OutboxDispatcherIT` (effectively once) |
| AC-CORE-11 | VERIFIED (sequential) | `ReviewIT`: a second approve gives 409 APPROVAL_CONFLICT; the unique index is the backstop. Not tested with truly concurrent threads |
| AC-CORE-12 | IMPLEMENTED, NOT VERIFIED (end to end) / BLOCKED (values, O-09) | Gateway caps tested in `ModelGatewayIT`; COST_CAP mapping tested in `AnalysisIT`; no cap values decided |
| AC-CORE-13 | VERIFIED | `WorkflowConsistencyIT`, `DefinitionVerifierTest`, Tx C checks in `AnalysisIT` |
| AC-CORE-14 | NOT IMPLEMENTED | Inbox and badge exist for BRD_RELEASED only; clarification assignments not built |
| AC-CORE-15 | VERIFIED (partial) | Security events: RESOURCE_NOT_FOUND, LINK_REQUEST_REJECTED, STAFF_ACCESS_DENIED, MFA_FAILED. Unauthorised approval attempts give 403 plus STAFF_ACCESS_DENIED. No alerting or aggregation |
| AC-CORE-16 | BLOCKED | Retention and tenant deletion: O-02/O-04/O-23/O-25 open; not built |
| AC-CORE-17 | IMPLEMENTED, NOT VERIFIED (this branch) | Compose config validated; the stack was not started (no Docker daemon here); CI has not run on this branch. CI was green on PR #6. No real LLM key is used by any test |

## AC-DOC

| AC | Status | Evidence / gap |
|---|---|---|
| AC-DOC-01 to 09 | VERIFIED | `DocumentApiIT`, `DocumentContentValidatorTest`, `EncryptedFileObjectStoreTest` (PR #4). Production store O-12 BLOCKED |
| AC-DOC-10 | VERIFIED | `ExtractionIT` (UNSUPPORTED_CONTENT); the guard blocks analysis (D-R5-02), so the BRD never claims analysis of these documents |
| AC-DOC-11 | VERIFIED (fake model) | `AnalysisIT`: a document citation of the PROCESSED chunk is verified, with the chunk label stored |
| AC-DOC-12 | VERIFIED (partial) | Rejected documents are excluded and listed in `brd_version.excluded_documents`. Documents needing a decision block analysis instead (T12/T13 not built) |
| AC-DOC-13 | VERIFIED | `AnalysisIT`: injected text travels only as data; the instructions are the registered prompt; the workflow is unaffected |
| AC-DOC-14 | VERIFIED (partial) | `AnalysisIT` stale test: the result is rejected, the execution marked STALE and re-run (T15). Proactive cancellation on upload after analysis is not built (D-R5-08) |
| AC-DOC-15 | VERIFIED (partial) | Approval binds content and input-set hash. STALE_INPUTS on approve; release re-check invalidates. Invalidation at the moment of change is not built |
| AC-DOC-16 / 17 | VERIFIED | `ExtractionIT` (PR #5) |
| AC-DOC-18 | VERIFIED | `RequirementRoutingIT` (MANUAL_INTAKE) |
| AC-DOC-19 | VERIFIED (partial) | Reviewers see quotes and references (`ReviewIT`); a separate audited "open source" endpoint is not built; the client view is verified |
| AC-DOC-20 | VERIFIED | The approver comes from the staff session; client submitters cannot approve; four-eyes on correction authors (`ReviewIT`) |

## AC-EXT

| AC | Status | Evidence / gap |
|---|---|---|
| AC-EXT-21 | VERIFIED | Guard 9.4 (`InputSets`), `AnalysisIT` |
| AC-EXT-22 | VERIFIED | `DocumentApiIT` scanner down; the guard blocks QUARANTINED documents |
| AC-EXT-23 | VERIFIED (partial) | T07 at validation (`RepositoryIntegrationIT`). T10 and the reviewer decision (T12/T13) are not built; the guard waits instead |
| AC-EXT-24 / 25 | VERIFIED | `ExtractionIT` |
| AC-EXT-26 | VERIFIED | `EvidenceVerifierTest` (missing version, other chunk, wrong quote, not in input set), `AnalysisIT` |
| AC-EXT-27 | VERIFIED | FLAGGED items (`EvidenceVerifierTest`, `AnalysisIT`); acknowledgement is required (`ReviewIT`) |
| AC-EXT-28 | VERIFIED | `RequirementRoutingIT` |
| AC-EXT-29 | VERIFIED | `ReviewIT`: the client view has no quotes; reviewers see quotes |
| AC-EXT-30 | BLOCKED | Retention (O-02/O-23/O-25) |
| AC-EXT-31 | NOT IMPLEMENTED | Reviewer document decisions (T12/T13) not built |
| AC-EXT-32 | VERIFIED (partial) | UPLOAD_FAILED blocks the guard; the client acknowledgement path is not built |
| AC-EXT-33 | VERIFIED | `DocumentApiIT` (malware) |
| AC-EXT-34 / 35 | VERIFIED | `DocumentApiIT`, ClamAV in CI (PR #4) |
| AC-EXT-36 | VERIFIED | `SchemaSecurityIT`, `RlsIsolationIT`, `RlsPoolLeakIT`, `JobQueueIT`. Production platform check (O-31) BLOCKED |
| AC-EXT-37 | BLOCKED | O-32 |
| AC-EXT-38 | VERIFIED | Grants and roles: the two-person database path (`StaffProvisioningIT`); approval by the submitter is impossible (separate identity type) |
| AC-EXT-39 | BLOCKED | O-02 retention values |
| AC-EXT-40 | VERIFIED | In-app notifications only (`ReviewIT`); there is no email code |
| AC-EXT-41 | VERIFIED | `AnalysisIT` stale test (WF-S02) |
| AC-EXT-42 | VERIFIED (partial) | MANUAL_INTAKE does not schedule extraction or BA. The Business Reviewer resume (T04) is not built |
| AC-EXT-43 | VERIFIED | `EvidenceVerifierTest` (path, line range, range hash, quote) |

## Summary

| Status | Count (60 criteria) |
|---|---|
| VERIFIED (13 of them qualified: partial, fake service, or limited scope; see rows) | 51 |
| IMPLEMENTED, NOT VERIFIED | 2 (AC-CORE-12 end to end, AC-CORE-17 on this branch) |
| BLOCKED | 4 (AC-CORE-16, AC-EXT-30, AC-EXT-37, AC-EXT-39); the real-service parts of AC-CORE-02/03 are also blocked |
| NOT IMPLEMENTED | 3 (AC-CORE-06, AC-CORE-14, AC-EXT-31) |

The release is **not** 100 % complete. Partial items are listed above.
