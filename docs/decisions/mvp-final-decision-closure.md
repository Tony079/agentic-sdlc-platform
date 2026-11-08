# MVP Final Decision Closure (2026-11-08)

This document lists every decision that affects MVP completion or production, with its state on 2026-11-08.
- **No owner decision was made or assumed by the implementer.**
- Where a provisional TDD default is in use, it is named and marked for confirmation.
- The full rationale is in `decision-register.md`.

## 1. Closed by owner decision (before this phase)

| ID | Decision | Recorded |
|---|---|---|
| C-01 / O-05 | Repository registration by internal staff with the `REPOSITORY_REGISTER` capability | Register §9, 2026-11-07 |
| C-18, Q-A, Q-B | Owner-only visibility; one-time approver link request; validation only to T06/T07 | §9 |
| O-06 | Global staff identities, assignment-gated active tenant, no automatic tenant access | §9 |
| O-11 | Mandatory TOTP MFA for staff | §9 |
| O-13 (shape) | Mutual-approval bootstrap for development/test; **two-step ceremony required before production (not implemented)** | §9 |
| S-02 | Grants only through the privileged path | §9 |

## 2. Implementer decisions in this phase (need owner confirmation where flagged)

| ID | Decision | Needs |
|---|---|---|
| D-R7-01 | Client clarifications are assigned to the requirement owner (TDD lists other client roles) | PO confirmation |
| D-R7-02 | Blocking clarification pauses the whole BRD commit | PO / Architecture Owner confirmation |
| D-R7-04 | Reviewer clarifications block approval while open | PO confirmation |
| D-R7-05 | Document decisions: reviewer EXCLUDE; owner acknowledges UPLOAD_FAILED exclusion | — (follows TDD) |
| D-R7-06 | Uploads allowed until INTERNAL_REVIEW; a new document invalidates the review version | PO confirmation |
| D-R7-08 | New outbox event `ClarificationAnswered` | Architecture Owner |
| D-R7-09 | Retention as a separate one-shot process and login (S-01 option a) | Security Owner confirmation |
| D-R7-10 | **Closure = SHARED_WITH_CLIENT, REJECTED or CANCELLED** (start of the retention clock) | **PO decision** |
| D-R7-11 | Retention durations = PRD development defaults 30/90/365/365 days | **Owner confirmation against contract and privacy (O-02/O-04/O-23/O-25)** |
| D-R7-13 | Tenant deletion procedure (operator command with confirmation) | **Who may authorise it: owner decision** |
| D-R7-15 | Minimal version diff as P0 (C-16 provisional) | **PO decision (C-16)** |
| D-R7-17 | Gateway tool calling; the BA agent still uses context assembly | Architecture Owner |

## 3. Provisional TDD defaults still in use

| ID | Default in use | Decision needed from |
|---|---|---|
| O-16 | Release mode AUTOMATIC (no manual release step) | Product Owner |
| O-08 | Flagged statements require acknowledgement per item before approval | Product Owner |
| C-09 | Client change request (T27) by the requirement owner; no client comment endpoint | Product Owner |
| O-22 / C-16 | Minimal statement-level diff, reviewer only | Product Owner |
| O-09 | Budget caps configurable; values are placeholders | Product Owner / Finance |

## 4. Open decisions that block MVP completion or production

| ID | Topic | Blocks | Owner |
|---|---|---|---|
| **O-01** | LLM provider selection (and the FR-LLM-10 evaluation) | Real analysis; AC-CORE-03 real verification | PO, Security Owner |
| **O-12 / C-13** | Production object store, KMS, key rotation | Production; SEC-REVIEW-2 | Security Owner, Operations |
| **O-13 (production)** | Two-step administrator ceremony: design accepted, not implemented | Any production staff | Security Owner |
| **O-03** | Production malware scanner | Production uploads | Security Owner |
| **O-15** | Extractor isolation | Real client documents | Security Owner |
| **O-02, O-04, O-23, O-25** | Retention periods, legal holds, data residency | Production retention values | PO, Legal / Privacy |
| **O-07 / O-31** | Hosting platform; production RLS platform check; backup rotation (FR-RET-06) | Production; AC-EXT-30 backups | Operations |
| **C-17** | Alert channel for security events | AC-EXT-33 "admins alerted"; AC-CORE-15 | PO, Security Owner |
| **O-32** | Admin access grants to tenant content | AC-EXT-37 | Security Owner |
| **O-27 / S-06** | Retry after failure (T26); no generic cross-tenant retry is built | Recovery from FAILED | PO, Security Owner |
| **C-06 / C-07** | Manual-intake resume (T04) and who may close (T05) | AC-EXT-28, AC-EXT-42 | PO |
| **C-04** | Document preview | FR-UPL-11; AC-DOC-05 preview clause | Architecture Owner, Security Owner |
| RPO / RTO | Recovery objectives | Backup design | Operations, business |
| Push / CI / deploy | Authorisation to push the branch, run CI, merge, deploy | AC-CORE-17; any release | Repository owner |

## 5. Explicitly not decided by the implementer

The implementer did not set any of the following:
- legal retention periods;
- data residency;
- RPO/RTO;
- business authorisation policies (who may delete a tenant, who may approve releases manually);
- provider choice;
- staff access to tenant content.
