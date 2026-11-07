# Decision Register

Tracks owner decisions, PRD/TDD conflicts, security findings and implementation decisions.
**PRD v0.7 is the product authority; TDD v1.0.4 is the architecture reference. Neither is modified here.**
Entries are only marked RESOLVED by a recorded owner decision or verified evidence.

Last updated: 2026-11-07 (model gateway review fixes, PR #6).

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
| MVP-PROG | 2026-11-04 | Owner instruction to complete all implementable MVP milestones in one programme, without approval stops between ordinary milestones; no push, PR, merge or retarget without separate authorisation | Open decisions stay open; blocked features stay behind secure gates. Scope conflict recorded as **C-19** |
| M3-Q1 | 2026-11-04 | Outbox: single-transaction row-lock dispatch (`FOR UPDATE SKIP LOCKED`, consumers in the same transaction under savepoints); no claim lease or dispatch epoch | Deviation from TDD 11.2 lease/epoch design for the database-only dispatcher |
| M3-Q2 | 2026-11-04 | Tenant-wise dispatch inside each tenant's RLS context; no privileged cross-tenant function, no SECURITY DEFINER | |
| M3-Q3 | 2026-11-04 | Separate worker login `sdlc_worker` with column-level grants; the API cannot mark events published/dead | |
| M3-Q4 | 2026-11-04 | **REJECTED** (deferring the job queue). The PostgreSQL job queue with leases, heartbeats, fencing epochs, bounded retries, backoff, dead-lettering and recovery is built in Milestone 3 (PRD R1-M1) | Job types get handlers in their own milestones |
| M3-Q5 | 2026-11-04 | DRAFT editable; SUBMITTED editable but the kind cannot change (409 `KIND_CHANGE_NOT_ALLOWED`); routed states not editable (interim policy) | |
| M3-Q6 | 2026-11-04 | No dead-event recovery API until the admin authorisation decisions (O-06/O-13); dead events recorded, audited, alerted and isolated per aggregate; no invented FAILED transition | A requirement whose routing event dies stays SUBMITTED |
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

### Implementation decisions (Milestone 3)

| ID | Decision | Rationale / limit | Status |
|---|---|---|---|
| D-M3-01 | One image, three processes: `api` (sdlc_app), `worker` (profile `worker`, sdlc_worker, no HTTP), one-shot `migrate` | Background privileges never reachable from an HTTP request | ACTIVE |
| D-M3-02 | The worker lists ACTIVE tenants from the global registry (columns `id`, `status` only) and then works inside one tenant's transaction context at a time | Q-M3-2 without a cross-tenant function; cost is one query per tenant per poll | ACTIVE |
| D-M3-03 | One event per dispatch transaction (LIMIT 1, SKIP LOCKED); per-aggregate order by "no earlier unpublished event of the aggregate" | Smallest blast radius per transaction | ACTIVE |
| D-M3-04 | Delivered and dead outbox events, finished jobs and closed attempts are final, enforced by invoker-rights BEFORE UPDATE triggers with a fixed `search_path` (no SECURITY DEFINER). Job policy is copied from `job_type` by a BEFORE INSERT trigger | D-M2-02 ("no triggers") still holds for the workflow tables; these guards protect queue/outbox integrity even against the superuser | ACTIVE |
| D-M3-05 | `RequirementSubmitted` carries the route code (`REPOSITORY` / `MANUAL_INTAKE`), derived from the kind at submission | The worker needs no access to requirement content; the kind is locked after submission (Q-M3-5) | ACTIVE |
| D-M3-06 | T02 creates stage execution REPO_VALIDATION (READY) and enqueues one REPO_VALIDATE job (idempotency key `repo-validate:<requirement>:1`). **No REPO_VALIDATE handler exists yet**: the job stays QUEUED and is reported as backlog until the repository milestone | Repository validation needs the GitHub integration (O-05, C-01, C-18) | ACTIVE — known limit |
| D-M3-07 | JobDead marks the stage execution FAILED only. Workflow FAILED transitions (T09, T19) arrive with the stages whose handlers can fail | Q-M3-6: no invented transition | ACTIVE |
| D-M3-08 | Operational visibility: Micrometer gauges/counters (outbox pending, dead, oldest pending age; queue backlog, leased, expired leases, dead jobs; dispatch/claim/retry/dead counters), a periodic `ops.snapshot` log line and `ALERT` log lines. No HTTP metrics endpoint yet | Exposing metrics needs an operator authentication model (O-06/O-13); C-17 alert channel still open | ACTIVE — known limit |
| D-M3-09 | T03 Business Reviewer notification is not sent | Staff accounts do not exist (O-06) | GATED by O-06 |
| D-M3-10 | Upgrade of an existing database needs the bootstrap scripts re-run (they are idempotent) before `migrate`, to create `sdlc_worker` | Fresh Compose volumes run them automatically | ACTIVE — operator note |
| D-M3-11 | All write commands lock the workflow instance before the requirement row (edit, submit, cancel, routing) | One lock order: no deadlocks, no edit after routing | ACTIVE |
| D-M3-12 | Stored workflow definition `R1-v1` (table `workflow_definition`) must equal the engine's compiled transition table; the api and worker refuse to start otherwise (WF-D01) | Scope guard TDD 9.10 | ACTIVE |

### Implementation decisions (Milestone 4: attachments)

| ID | Decision | Rationale / limit | Status |
|---|---|---|---|
| D-M4-01 | Object storage is an `ObjectStore` port with one adapter: a private **encrypted file store** (AES-256-GCM per 64 KiB chunk, per-tenant HKDF-style key from a 32-byte master key `SDLC_STORAGE_KEY`, opaque `<tenant>/<uuid>` keys, atomic writes) on a volume shared by api and worker | PRD FR-STO-01 allows "an equivalent storage abstraction"; the production object store, its licence (MinIO terms) and KMS are **O-12 / C-13** (open). No S3 adapter is built yet | ACTIVE — production store gated by O-12 |
| D-M4-02 | The API stores the file in quarantine and records the version; it emits `DocumentUploaded`. The worker enqueues and runs `DOCUMENT_SCAN` (TDD job DOC_VALIDATE_SCAN): decrypt into a per-job/epoch workspace, verify hash and size, validate structure, then scan; workspace deleted in every case; abandoned workspaces swept after 24 h | Keeps the API without queue privileges (Q-M3-3) | ACTIVE |
| D-M4-03 | The API inserts versions without state or scan columns (column default QUARANTINED) and has no UPDATE on versions; only the worker records results. The database refuses any analysable state without a CLEAN scan (`document_version_clean_scan_required`), and a recorded scan result is immutable (trigger) | SEC-DOC-03 enforced in the database, not only in code | ACTIVE |
| D-M4-04 | Intermediate states VALIDATING/SCANNING are not written; a version goes QUARANTINED → READY_FOR_EXTRACTION / REJECTED_INVALID / REJECTED_MALWARE in one fenced commit; scanner outage keeps it QUARANTINED (job deferred, no retry budget used, alert after 5 minutes) | Fewer non-transactional writes; fail-closed behaviour unchanged | ACTIVE |
| D-M4-05 | Removal only in DRAFT (soft: `removed_at`); object deletion is a retention concern (O-02). Replacement after submission (new document version, FR-UPL-10) and staleness handling arrive with analysis (9.6) | | ACTIVE — known limit |
| D-M4-06 | Uploads allowed in DRAFT, SUBMITTED, MANUAL_INTAKE (PRD 0.4) and REPO_VALIDATING; refused in terminal states (`409 DOCUMENTS_LOCKED`) | | ACTIVE |
| D-M4-07 | Download only after a clean scan, as `application/octet-stream` attachment with `nosniff` and `no-store`, through the API (no presigned URLs); audited. In-app preview (FR-UPL-11) is **not built** | C-04 open | GATED by C-04 |
| D-M4-08 | Malware: version REJECTED_MALWARE, HIGH security event `MALWARE_DETECTED`, `ALERT` log line with the signature name (never shown to clients). The Platform Admin alert channel is C-17 (open) | AC-EXT-33 alert evidence partial | ACTIVE |
| D-M4-09 | No ClamAV daemon in the development container: integration tests use a protocol-level clamd test double (`FakeClamd`, real INSTREAM protocol); CI's Compose smoke test uses the real `clamav/clamav:1.4` image with the EICAR test string | Honest evidence split | ACTIVE |

### Implementation decisions (Milestone 5: document processing)

| ID | Decision | Rationale / limit | Status |
|---|---|---|---|
| D-M5-01 | **Extraction is behind a secure gate**: `sdlc.documents.extraction.enabled` defaults to **false**; local Compose and tests switch it on for synthetic documents only | **O-15 OPEN — BLOCKING**: the M3S isolation spike has not run; no isolation claim is made | GATED by O-15 |
| D-M5-02 | The parser runs in a separate OS process (`ExtractorMain`, PDFBox 3.0.5 for PDF, hardened StAX for DOCX, strict decoding for TXT/Markdown) with its own 256 MB heap, one CPU, a wall-clock limit, an **empty environment**, `/dev/null` stdin and a private per-job/epoch workspace; killed on timeout | Process isolation only. Container isolation (non-root, read-only root, no network, seccomp, extractor/coordinator pairs, TDD 15.7) is **not built** and is part of O-15 | ACTIVE — partial isolation |
| D-M5-03 | The coordinator treats extractor output as untrusted: manifest schema and outcome list, SHA-256 of the chunk file, sequential ordinals, size/length caps, strict UTF-8; anything else is EXTRACTION_FAILED_FINAL `OUTPUT_INVALID` and nothing is stored | TDD 15.6 | ACTIVE |
| D-M5-04 | Deterministic outcomes are final and not retried: encrypted → UNSUPPORTED_CONTENT `ENCRYPTED`; no text layer (image-only, no OCR) → UNSUPPORTED_CONTENT `NO_TEXT_LAYER`; corrupt or over 2 M characters / 5,000 chunks → EXTRACTION_FAILED_FINAL. Transient failures (timeout, crash, I/O) use the job type's 3 attempts; a dead job ends EXTRACTION_FAILED_FINAL `EXTRACTION_RETRIES_EXHAUSTED` | PRD AC-EXT-24, AC-DOC-10, FR-UPL-12 | ACTIVE |
| D-M5-05 | States EXTRACTING and EXTRACTION_FAILED (retry pending) are not written; READY_FOR_EXTRACTION goes directly to its final state in the fenced commit | Same reasoning as D-M4-04 | ACTIVE |
| D-M5-06 | No extraction is scheduled for a requirement in MANUAL_INTAKE (WF-M01); scheduling on resume (T04) waits for O-06. Documents scanned while the gate was off are not re-enqueued automatically when it is switched on | | ACTIVE — known limit |
| D-M5-07 | Chunks (max 2,000 characters, page or heading label, SHA-256) are stored in `document_chunk` with a `simple` full-text index; worker-only grants; no embeddings (FR-DPR-09) | The API cannot read extracted text yet; evidence views come with the BRD | ACTIVE |

### Pre-implementation verification (Milestone 5)

| ID | Check | Result |
|---|---|---|
| V-M5-1 | Extractor launch from the packaged Spring Boot jar | **Finding (fixed before commit):** with `java -jar <relative path>` the class path was relative and the extractor (started in its own working directory) could not find the jar: every job failed `EXTRACTOR_CRASHED` and went dead, while tests (absolute class path) passed. Found by running the real jar end to end; class-path entries are now made absolute. Re-verified: Markdown upload → PROCESSED with heading label through the packaged jar |

### Pre-implementation verification (Milestone 4)

| ID | Check | Result |
|---|---|---|
| V-M4-1 | Fail-closed CHECK on scan results | **Finding (fixed before commit):** the first version used `OR scan_result = 'CLEAN'`, which evaluates to NULL (and therefore passes) when no scan exists; a superuser UPDATE to READY_FOR_EXTRACTION succeeded. Found by `DocumentApiIT.scanResultsCannotBeForgedOrSkipped`. Fixed with `IS NOT DISTINCT FROM 'CLEAN'` and a named constraint; changeset 0014 had never been applied outside throwaway test databases. All other CHECKs reviewed for the same pattern |

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
| S-06 | TDD archive: REPO_VALIDATING retry safeguard (S-27) not implemented | Milestone 3 schema has **no admin-retry path at all**: no retry columns on `job_type`, no runtime role can write `job_type`, the transition CHECK has no retry transition, and the engine refuses a stored definition with T30 (`DefinitionVerifierTest`) | **OPEN** for the archive; no admin retry is built (O-06/O-13) |
| S-03, S-05, S-07..S-12 | See planning record | — | OPEN |

## 6. Acceptance-criteria progress

### Milestone 5

No acceptance criterion is claimed complete. Evidence toward: AC-DOC-10 (image-only and encrypted PDFs are not
analysed), AC-DOC-16 (processing failure contained, workflow unchanged), AC-DOC-17 (idempotent extraction: state check
under lock, unique keys), AC-EXT-24 (three attempts with backoff, transient errors only), AC-EXT-42 precondition (no
extraction in MANUAL_INTAKE), FR-DPR-01..10 (FR-DPR-07 only at process level, O-15). **Real client documents remain
blocked by O-15.**

### Milestone 4

No acceptance criterion is claimed complete. Evidence toward: AC-DOC-01 (multiple uploads with status; the
submission-with-documents flow), AC-DOC-02/03 (invalid, renamed executables and archives rejected by content), AC-DOC-04
and AC-EXT-22 (scanner outage keeps files QUARANTINED; real-ClamAV path in CI only), AC-DOC-05/06 (cross-tenant and
other-user access denied with security events), AC-DOC-07 (uploader, time, audited upload/download/removal), AC-DOC-08
(duplicates), AC-DOC-09 (no content in PostgreSQL, opaque keys, private encrypted storage; production store O-12),
AC-EXT-32/33/34/35 (gating, malware handling and alert, limits, scanner abstraction). Not yet: preview (C-04),
UPLOAD_FAILED rows, versioning after submission.

### Milestone 3

No acceptance criterion is claimed complete. Evidence toward: AC-DOC-18 / AC-EXT-28 (new product routed to MANUAL_INTAKE
with no job scheduled; the Business Reviewer resume/close part waits for O-06), AC-CORE-10 (crash yields one result:
outbox crash-before-commit and job lease-loss recovery tests), AC-EXT-36 jobs clause (C-14: the worker sets the tenant
context before every data access; cross-tenant claim/commit refused), AC-CORE-13 (engine-only routing transitions,
refused by the database for a user actor), FR-WF-01/09, PRD R1-M1 (job queue and outbox).

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

## 7. Model gateway and publication (2026-11-05)

### Implementation decisions (model gateway foundation, branch `claude/model-gateway`)

| ID | Decision | Rationale / limit | Status |
|---|---|---|---|
| D-GW-01 | Module `modelgateway`, port `ModelGateway.generate(ModelRequest)`, SPI `ModelProvider` (TDD 13.1 names the port `ModelClient.generate`; same contract) | Agents depend only on the port (FR-LLM-01/02) | ACTIVE |
| D-GW-02 | Provider and model come only from server configuration (`sdlc.model.provider`, `sdlc.model.model-id`); default `none`, so every call fails with CONFIGURATION_ERROR and no data leaves the platform while **O-01** is open; an unknown provider stops start-up | No production provider is hard-coded | ACTIVE |
| D-GW-03 | Tenant from the trusted tenant context only; calls are refused without a tenant and inside a database transaction | Slow calls never hold locks or connections | ACTIVE |
| D-GW-04 | Only registered, ACTIVE prompt versions run: (id, version, SHA-256) must match `prompt_version` | An edited prompt needs a reviewed new version (FR-LLM-07) | ACTIVE |
| D-GW-05 | Output binds strictly to a typed record (unknown, missing or trailing content rejected) and is checked with Bean Validation; one repair attempt with fixed trusted text that never quotes the invalid output | FR-LLM-06, TDD 13.4 | ACTIVE |
| D-GW-06 | Per-attempt timeout enforced by the gateway; timeouts and unavailability retried up to `max-transient-retries` (default 2) then TIMEOUT / PROVIDER_UNAVAILABLE (transient, for job deferral); rate limit returned at once as RATE_LIMITED; other provider errors not retried | TDD 13.5 | ACTIVE |
| D-GW-07 | `model_call_record` (tenant RLS, worker-only) stores provider, model, prompt version, outcome, attempts, tokens, cost, latency, request and correlation ids; never prompt, data or output text. Cost from `model_registry` prices | FR-LLM-04, TDD 13.6 | ACTIVE |
| D-GW-08 | Budget caps (tenant per month, per requirement) are configurable and empty by default | Values are **O-09** (open) | ACTIVE — values open |
| D-GW-09 | Tool calling is **not** in the gateway yet; it belongs with the agent runtime's tool registry in R1-M5 (FR-LLM-01 partially met) | Avoids a speculative API | KNOWN GAP |
| D-GW-10 | `FakeModelProvider` is deterministic (scripted or canned replies, token estimate ceil(chars/4), no network, no dependencies); `model_registry` CHECK ties provider `fake` to data class FAKE | FR-LLM-09 fake provider; the hosted adapter waits for O-01 | ACTIVE |
| D-GW-11 | Usage provenance: each record carries `usage_source` (REPORTED, ESTIMATED, UNAVAILABLE, NONE). Tokens and cost are NULL unless the source is REPORTED or ESTIMATED (database CHECK). The fake provider's counts are ESTIMATED, never presented as provider measurements | Review finding GW-R1 | ACTIVE |
| D-GW-12 | Provider calls run on a bounded executor (`sdlc.model.max-concurrent-calls`, default 16); when every slot is busy a call fails as PROVIDER_UNAVAILABLE (transient) | Review finding GW-R2 | ACTIVE |
| D-GW-13 | Registered prompt versions are immutable in the database (trigger): no edit of id, version, agent, hash or schema version; no delete; ACTIVE → RETIRED only, RETIRED final | Review finding GW-R3 | ACTIVE |
| D-GW-14 | Budget caps sum recorded `cost_usd`; calls with unknown usage (UNAVAILABLE) add nothing to the sums. Whether an unknown-cost call should count conservatively is part of **O-09** | Known limit | OPEN with O-09 |

### Findings during publication and verification

| ID | Finding | Resolution | Status |
|---|---|---|---|
| V-CI-1 | First CI run of PRs #3–#5: Compose step "Worker routes ... (Milestone 3)" failed. Cause: the step expected REPO_VALIDATING, but the Milestone 2 smoke requirement is NEW_PRODUCT and was correctly routed to MANUAL_INTAKE (worker snapshot showed no pending events and no jobs). Reproduced locally with the packaged jar on a fresh database | Assertion corrected (commit `bf4318f`), merged forward into M4/M5 with merge commits (no rebase, no force-push). Later steps (real ClamAV, extraction) had not run in the first CI attempt because the job stopped at this step | RESOLVED — CI green on `bf4318f`, `ab1ac05`, `7e98a34` |
| SEC-M3-01 | The API logged "Using generated security password": Spring Boot created an unused in-memory user (form login and HTTP Basic disabled, so not usable). Present since Milestone 1 | `UserDetailsServiceAutoConfiguration` excluded; `NoDefaultUserIT` guards it. Fixed on the new branch; PR #1 is unchanged | FIXED on PR #6 (`claude/model-gateway`); CI-verified on `50528e1` (`NoDefaultUserIT`, no generated-password line in CI logs). Not on PR #1–#5 lineage |
| SEC-REVIEW-1 | Extractor subprocess runs as the worker's OS user inside the worker container: no separate container, no seccomp, network not blocked at OS level | Recorded under O-15 (extraction gate default off) | OPEN (O-15) |
| SEC-REVIEW-2 | Storage encryption key (`SDLC_STORAGE_KEY`) is a single static master key from the environment; no rotation or KMS | Recorded under O-12/C-13 | OPEN (O-12/C-13) |
| SEC-REVIEW-3 | `clamav/clamav:1.4` runs with the image defaults; not reviewed for least privilege; dev/CI scanner only | Production scanner is O-03 | OPEN (O-03) |
| GW-R1 | Pre-publication review of the gateway: missing provider usage was recorded as 0 and the fake provider's estimated counts were indistinguishable from provider measurements | Fixed before first push: nullable tokens/cost and `usage_source` (D-GW-11); `missingUsageIsRecordedAsUnknownNotZero` | FIXED (changeset 0016 corrected in place: never pushed or applied outside test databases) |
| GW-R2 | Pre-publication review: the provider-call executor was an unbounded cached pool; a provider ignoring interruption after a timeout leaks a thread per call | Bounded executor (D-GW-12); `concurrentCallsAreBounded` | FIXED |
| GW-R3 | Pre-publication review: `prompt_version` rows could be edited in place (only the code-side hash check protected them) | Immutability trigger (D-GW-13); `registeredPromptVersionsAreImmutable` | FIXED |

## 8. Outstanding decisions — structured view (2026-11-05)

Original IDs and descriptions are preserved above; this table adds owner, options, impact and gates. **No entry is
resolved here.** "Recommendation" is given only where an earlier planning record (pre-M0 plan, 2026-11-02) made one.

| ID | Description | Owner | Options | Recommendation | Implementation impact | Blocked milestones | Decision deadline | Status | Evidence of resolution |
|---|---|---|---|---|---|---|---|---|---|
| C-01 / O-05 | Who registers a GitHub repository (PRD "admin" vs TDD Client Approver; TDD self-inconsistent) | PO, SO | A Client Approver; B Platform Admin (needs O-06/O-13); C owner registers + approver confirms (PRD clarification) | None made | Repository registration API authorisation; `sourcerepo` module | **R1-M4** (and so R1-M5, R1-M6) | Before R1-M4 starts | OPEN — BLOCKING | Signed decision record; PRD/TDD alignment note |
| O-06 | Internal staff access model (global staff, assignments, active tenant) | SO, PO | A global staff + assignment-gated active tenant; B per-tenant staff accounts; C tenant per request (conflicts FR-AUTH-06); D tenant fixed at login; E staff see all (rejected) | Pre-M0 plan: A with conditions | `identity` staff model, reviewer UI, T04/T05, T21–T23 | **R1-M6**; R1-M4 if C-01 = B | Before R1-M6 (or R1-M4 if B) | OPEN — BLOCKING | Accepted text per item (pre-M0 brief) |
| O-11 | MFA for internal staff | SO | A none; B TOTP; C WebAuthn; D MFA for all users | Pre-M0 plan: B for all staff | Staff login | **R1-M6** | Before any staff login | OPEN | Accepted factor, roles, recovery procedure |
| O-13 | Provisioning flow and two-admin bootstrap | PO, SO | A mutual-approval ceremony; B single first admin (conflicts FR-AUTH-22); C env bootstrap (rejected); D two-step ceremony with admin functions | Pre-M0 plan: A for dev/test, D before production | Admin bootstrap, tenant/user provisioning, recovery APIs (dead events/jobs, Q-M3-6) | **R1-M6**; R1-M4 if C-01 = B | Before R1-M6 | OPEN — BLOCKING | Accepted ceremony + evidence record |
| O-32 | Platform-admin access-grant policy to tenant content | PO, SO | (TDD 19.8 design or alternatives) | None | Admin read of tenant content | R1-M6 (AC-EXT-37), Gate G1 | Before Gate G1 | OPEN — BLOCKING for G1 | Accepted policy; AC-EXT-37 tests |
| C-07 | Platform Admin cancellation in MANUAL_INTAKE | PO | Proposed: admin cancel with mandatory reason, immutable audit | Proposal recorded 2026-11-03 | T29 by admin | R1-M6 admin features | Before admin features | OPEN (proposed) | Product Owner confirmation |
| C-18 | Client Approver visibility of Client Users' requirements | PO | A owner-only (current); B tenant-wide for approvers; C approver access limited to repository linking | None | Requirement/document visibility, repository linking | **R1-M4** (if approver links repositories) | Before R1-M4 | OPEN | Signed decision; visibility tests updated |
| D-M1-06 | Liquibase 5 licence acceptability | Owner | A accept Liquibase 5; B pin Liquibase 4.x; C other tool | None | Migration tooling | Production | Before production | OPEN | Owner confirmation of licence |
| CP-2 | SQL archive baseline selection | Owner | Supply newer 9-file set, or select original archive, or keep own schema | Own schema in use (D-M1-01) | Archive reuse only | None (archive not used) | Only if the archive is to be used | OPEN — BLOCKED | Signed reconciliation + baseline choice |
| S-01 | Archive: app role can purge audit | SO | Dedicated retention login; admin-ops function; PRD interpretation | Pre-M0 plan: dedicated retention login | Retention milestone | Retention (R1-M6 scope) | Before audit retention | OPEN (archive); own schema has no purge | Catalog test: runtime login cannot purge |
| S-02 | Archive: app role can write platform role assignments | SO | Role grants only through privileged admin path; revoke runtime writes | Pre-M0 plan: privileged path | Staff roles | **R1-M6** | Before staff roles exist | OPEN | Catalog + negative tests |
| S-06 | Archive: REPO_VALIDATING retry safeguard not implemented | SO, PO | Implement S-27 set; or keep no retry path | Own schema: no retry path exists | Admin retry | R1-M4 (T09), admin retry | Before any admin retry | OPEN (archive) | S-27 tests if retry is ever built |
| O-15 | Extractor isolation spike (M3S) | SO, AO | Container pair per TDD 15.7; microVM; other | None (spike decides) | `docprocessing` extractor runtime; extraction gate | Real client documents (onboarding); R1-M5 on real data | Before any real client document | OPEN — BLOCKING | M3S report reviewed by SO; architecture decision |
| O-12 / C-13 | Production object store, licence, encryption at rest / KMS | SO, AO | S3-compatible managed store; MinIO (licence re-check); other | None | `storage` adapter, key management | Production / onboarding | Before production data | OPEN | Store chosen; encryption and key rotation verified |
| O-01 | LLM provider and model | PO | Evaluate OpenAI, Anthropic, Google per FR-LLM-08/10/11 | None (evaluation decides) | Hosted `ModelProvider` adapter, `model_registry` row, data-handling terms | **R1-M5** real BRDs | Before R1-M5 on real data | OPEN | Evaluation report (TDD 13.7) + PO decision |
| C-19 | Programme brief lists Release 2/3 items (estimation, pricing, estimate acceptance, client scope approval, Architect Agent, TDD generation) | PO | Keep PRD v0.7 scope; or change the PRD | PRD prevails | Scope guard (ArchUnit) unchanged | None in Release 1 | — | OPEN | A new PRD version, if scope changes |
