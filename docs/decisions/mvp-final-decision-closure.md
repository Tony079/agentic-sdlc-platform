# Remaining Owner Decisions and Blockers (Deliverable G, 2026-11-08)

This list contains **only** what is still open after the owner decisions of 2026-11-08. Decisions already approved are not asked again; they are recorded in `decision-register.md` §14 and `owner-decision-implementation-matrix.md`.

## 1. Closed by the 2026-11-08 approval (for reference only)

These are approved and implemented:
- O-01 (adapters and benchmark), O-12/C-13, O-13, C-17, O-03 (port), O-15, O-25, C-06/C-07, O-16, C-09, C-16, O-08, O-27.
- Earlier: C-01/O-05, C-18, Q-A, Q-B, O-06, O-11, S-02.

These earlier provisional choices are now **superseded**:

| Earlier item | Superseded by |
|---|---|
| D-R6-03 (automatic release) | O-16, D-R8-08 |
| D-R6-06 (owner makes T27 directly) | C-09, D-R8-14 |
| D-R7-10 (closure included SHARED_WITH_CLIENT) | O-25, D-R8-03 |
| D-R7-11 (durations unconfirmed) | O-25, D-R8-04 |
| D-R7-13 (physical tenant deletion; authorisation open) | O-25, D-R8-05 |

## 2. Genuine open decisions

| # | Decision | Why it is open | Blocks | Owner |
|---|---|---|---|---|
| G-01 | **O-07 hosting platform** (and with it the managed KMS, object store, backup rotation and log retention platform) | Not decided; O-12 approved the abstraction, not a product | Production start (KMS), B3/B4, AC-EXT-30 backups, D8, D9 | Operations, Security Owner |
| G-02 | **O-32 admin access grants to tenant content** | No policy approved; not invented | AC-EXT-37 | Security Owner |
| G-03 | **O-01 provider selection** | The approval covers adapters and a benchmark, not a winner. The benchmark needs keys | Real analysis (AC-CORE-03 real verification) | PO, Security Owner |
| G-04 | **O-03 product choice** for dependency and SAST scanning | Port built; no product named | Requiring those scan kinds at release | Security Owner |
| G-05 | **Completion state for released requirements** | Release 1 has no COMPLETED state, so released requirements are never "closed" and their retention never starts (FR-RET-16 says closure includes "completed") | Retention of released requirements; AC-EXT-30 (partly) | PO |
| G-06 | **Reopening** of a closed or cancelled requirement | The approval said reopening is separate; no transition is defined | — (functionality absent by design) | PO |
| G-07 | **Legal hold** | Not part of O-25; retention deletes on schedule | Retention for litigated tenants | Legal, PO |
| G-08 | **Physical purge after tenant soft deletion** | O-25 approved soft deletion only | Final erasure on request | Legal, Privacy |
| G-09 | **RPO and RTO** | Not invented | Backup design, D7 | Operations, business |
| G-10 | **O-09 budget cap values** | Placeholders | AC-CORE-12 values | PO, Finance |
| G-11 | **C-04 document preview** | Not designed | AC-DOC-05 preview clause | Architecture, Security |

## 3. Implementer choices awaiting confirmation (non-blocking)

| ID | Choice |
|---|---|
| D-R8-15 | Default priority mapping of the scope diff |
| D-R8-07 | Retry windows: 24 h by default, 6 h for scan, extract and validate |
| D-R8-10 | Alert rule thresholds (`security-alerting.md` §2) |
| D-R7-01, D-R7-02, D-R7-04, D-R7-06 | Clarification and upload rules (unchanged since the previous report) |
| O-08 (TDD meaning) | Per-item acknowledgement of flagged BRD statements stays the provisional TDD default (the 2026-11-08 O-08 concerned feature flags) |

## 4. Non-decision blockers

| # | Blocker | Needed |
|---|---|---|
| N-01 | Push authorisation (no CI run on this branch) | Owner |
| N-02 | Docker host for `docker compose up` | Environment |
| N-03 | GitHub App credentials and test organisation | Owner |
| N-04 | LLM API keys for the benchmark | Owner |
| N-05 | Managed KMS and object-store adapters (after G-01) | Implementation |
| N-06 | Alert credentials as secret files (R-04) and a re-wrap command (R-05) | Implementation |
| N-07 | Independent security review, image and dependency scans, backup and restore drill | Owner, security, operations |

## 5. Explicitly not decided by the implementer

The implementer did not set any of the following:
- legal retention periods or legal hold;
- data residency;
- RPO/RTO;
- the admin access-grant policy;
- the provider choice;
- the hosting platform;
- scanner products;
- staff access to tenant content.
