# Owner Decisions — Baseline Gap Matrix (2026-11-08)

| | |
|---|---|
| Branch | `feature/mvp-production-readiness` at `60b3d99` (clean tree) |
| Baseline tests | 263 backend tests (75 unit + 188 integration) and 36 frontend tests, all passing (`docs/reports/final-mvp-completion-report.md`) |
| PR branches #1–#6 | Unchanged |

Every claim below was checked against the code, not taken from earlier reports.

## 1. Decision-ID mismatches (recorded, not silently resolved)

The prompt's decision IDs differ from the TDD v1.0.4 register in four places. The approved behaviour in the prompt is implemented as written; the TDD meaning is kept where it is still relevant.

| ID | TDD v1.0.4 meaning | Prompt meaning (implemented) | Effect |
|---|---|---|---|
| O-08 | Flagged-fact acknowledgement policy (BRD) | Feature-flag change acknowledgement | The BRD flag acknowledgement (TDD 16.7 provisional default) is unchanged and stays provisional |
| O-16 | Client release mode (AUTOMATIC/MANUAL) | Manual release approval with `RELEASE_APPROVE`, bound to version and commit SHA | Applied to the Release 1 release step, BRD release to the client (T25). Release becomes MANUAL |
| O-25 | Repository snapshot refresh policy | Retention, closure and tenant deletion | Snapshot refresh stays "pinned for the requirement's life" |
| C-16 | Diff priority P0 (FR-UI-02) vs P1 (FR-DOC-06) | Structured scope diff with Critical/High/Medium/Low categories | Implemented for change requests; the BRD statement diff (D-R7-15) stays |

## 2. Approved decisions: state at baseline

| Decision | Baseline state (verified in code) | Gap |
|---|---|---|
| 4.1–4.3 Architecture, engine, RLS | Present: modular monolith, PostgreSQL job queue with lease/epoch fencing, outbox, forced RLS, composite tenant FKs | None |
| 4.4–4.6 Staff identity, MFA, S-02 | Present (`StaffAuthIT`, `StaffProvisioningIT`) | New capabilities needed |
| 4.7 / 5.3 O-13 production bootstrap | **Missing**: `bootstrap-admins` is development/test only | Two-person, time-limited, single-use ceremony; recovery |
| 4.8 Approvals | Present (hash-bound, invalidated on change) | — |
| 4.9 Clarifications | Present (`ClarificationIT`) | — |
| 4.10 Repository / C-01 / C-18 / Q-A / Q-B | Present (`LinkRequestIT`, `RepositoryIntegrationIT`) | No plaintext-token log test |
| 4.11 R1-M4/M5 boundary | Present | — |
| 5.1 O-01 provider evaluation | Provider-neutral gateway and fake provider only | OpenAI and Anthropic adapters; benchmark harness |
| 5.2 O-12/C-13 storage/KMS | Encrypted local file store with a single static key | Envelope encryption via a KMS boundary; secrets provider; fail-closed production mode |
| 5.4 C-17 alerting | Events persisted (`platform_security_event`); no alerting | Rules, deduplication, rate limit, webhook/email delivery, retry, status |
| 5.5 O-03 scanner | Malware scanner port only | Dependency, secret and SAST scanner port; normalised findings; release gate |
| 5.6 O-15 extractor isolation | Subprocess with the worker's environment | Isolated launch: environment cleared, memory/CPU/time limits, no network (namespace), per-job directory, fail closed |
| 5.7 O-25 retention | `retention` process (`sdlc_retention`): 30/90/365/365 days counted from "closure" = SHARED_WITH_CLIENT/REJECTED/CANCELLED | New defaults; closure-based; logs (infrastructure) |
| 5.8 Closure definition | Inferred from the current state | Immutable closure record (time, actor, terminal state) after an eligibility check; SHARED_WITH_CLIENT is no longer "closed" |
| 5.9 Tenant soft deletion | **Conflict:** `delete-tenant-data` physically deletes content | Replace with a two-person soft-deletion workflow; remove physical deletion |
| 5.10 C-06/C-07 | T04/T05 missing | Resume (T04) and close (T05); reopening stays forbidden without its own transition |
| 5.11 O-16 | Automatic release T25 | Manual release approval with `RELEASE_APPROVE`; separate execution |
| 5.12 C-09 | Direct T27 by the owner | Formal change request: submit, BA assessment, reviewer review, Client Approver decision, scope versions |
| 5.13 C-16 | Statement diff of BRDs | Scope diff with priority categories |
| 5.14 O-08 feature flags | None | Flag registry, changes, acknowledgement with a capability |
| 5.15 O-27 retry | Backoff with jitter, per-type attempts, DEAD state; no maximum duration; no manual retry | Maximum retry duration per job type; operator retry (T26) with `JOB_RETRY` |

## 3. Conflicts to record

| Item | Conflict | Smallest safe implementation |
|---|---|---|
| C-17 vs AC-EXT-40 | AC-EXT-40: "Release 1 sends in-app notifications only". C-17 approves email and webhook **security alerts to operators** | Alert delivery reaches operators only, is off by default and is configured per environment. User notifications stay in-app only |
| C-09 vs C-18 | The Client Approver must decide on a change request, but C-18 keeps requirement content owner-only | The approver sees the change request (requested change, assessment, scope diff) only once it awaits a client decision, never the full requirement |
| 5.8 closure vs Release 1 states | Release 1 has no COMPLETED state; SHARED_WITH_CLIENT allows further changes (T27) | Closure = CANCELLED or REJECTED only. A released requirement is not closed, so its retention clock does not start. A completion state is a PO decision |
| O-25 retention vs PRD FR-RET-15 | 30 days for temporary artifacts vs PRD 24 h for abandoned workspaces | Keep the stricter 24 h workspace sweep. Apply 30 days to repository snapshots and extracted text after closure |
| O-27 vs PRD transitions | The PRD allows retry only via T26 (FAILED → ANALYSING, BA jobs); PC-01 (retry after repository failure) is not approved | Manual retry for BA jobs only, through T26 |

## 4. Implementation sequence

1. Capabilities, T04/T05, closure record.
2. Tenant soft deletion and retention update.
3. O-13 ceremony.
4. Retry, manual retry, release approval, feature flags.
5. Alerting, scanner, extractor isolation.
6. Change requests and scope diff.
7. Storage, KMS and secrets.
8. Provider adapters and benchmark harness.
9. Frontend.
10. Regression and documents.
