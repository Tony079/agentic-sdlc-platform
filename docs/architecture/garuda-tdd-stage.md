# Garuda: Technical design (TDD) stage

Decision GD-26 (owner, 2026-11-17): an approved BRD moves the requirement to the next stage, **Technical design**.
An **Architect agent** reads the approved BRD and the analysed code and writes a technical design document, which
the project reviews and approves in the same way as the BRD. Estimation follows in a later stage.

## 1. Lifecycle (updated by GD-27, 2026-11-18)

```
BRD approved ──► handed to the project's Technical Approvers (notification + email; Organisation Admins if none)
             ──(Technical Approver: "Start technical design")──► GENERATING
GENERATING ──(agent output verified)──► IN_REVIEW
IN_REVIEW ──(Technical Approver decides flagged items, confirms approving alone, approves)──► APPROVED
IN_REVIEW ──(author or Technical Approver requests changes)──► new version GENERATING (old: SUPERSEDED)
GENERATING ──(agent failed after retries)──► FAILED ──(Technical Approver retries, 3 attempts)──► GENERATING
```

- **Owned by Technical Approvers (GD-27).** A project role; a member can hold it together with other roles. Only a
  Technical Approver starts, retries, decides flagged items and approves. One may approve alone: the approval needs an
  explicit confirmation, recorded on the approval ("approved by a single Technical Approver after explicit
  confirmation; no second reviewer") and printed in the exported design. The author follows the design and may
  request changes.
- **Started explicitly, not automatically.** Each run costs model tokens, so a Technical Approver starts it from the
  approved BRD. Only one design run can be in progress per requirement.
- The requirement's workflow (the BRD lifecycle, T01–T29) is unchanged: the requirement stays at "BRD approved", and
  the design has its own lifecycle (`tdd_version.status`). The requirement page shows the design stage as the
  requirement's current stage.
- **The BRD stays the source of truth for scope.** A change to scope goes through a BRD change request. When a newer
  BRD version is approved, the existing design is marked **based on an older BRD** and the author can regenerate it.

## 2. Inputs (all frozen; nothing new is fetched)

| Input | Source |
|---|---|
| Approved BRD | The released `brd_version` and its items (statements, sections, item keys) |
| Requirement | The current requirement revision |
| Clarification answers | The resolved clarifications of the requirement |
| Code | The **same pinned snapshots** the BRD was analysed at (`analysis_input_set.sources`), so the BRD and the design describe the same code |
| Change requests | The text of the request that produced a new version |

## 3. The Architect agent

Two calls through the model gateway, with the same safeguards as the BA agent (fixed versioned prompts, data blocks
marked untrusted, strict JSON schema, Bean Validation, one repair attempt, cost caps, usage records):

1. `arch.phase-a`: from the BRD and the repository inventory, choose the files the design must read.
2. `arch.design`: produce the design proposal.

Proposal shape:

```json
{ "summary": "...",
  "items": [ { "section": "ARCHITECTURE", "kind": "DECISION", "title": "...", "detail": "...",
               "component": "...", "brdRefs": ["<BRD item key>"],
               "evidence": [ { "type": "REPOSITORY", "path": "owner/repo/path", "startLine": 1, "endLine": 9,
                               "documentVersionId": null, "chunk": null, "quote": "..." } ] } ],
  "openQuestions": ["..."] }
```

- **Sections:** ARCHITECTURE, COMPONENTS, API_CHANGES, DATA_MODEL, INTEGRATIONS, SECURITY, NON_FUNCTIONAL,
  MIGRATION_AND_ROLLOUT, TEST_PLAN, RISKS.
- **Kinds:** DECISION (a design choice and why), CHANGE (a change to existing code), NEW (a new component, endpoint
  or table), TASK (implementation or test work), RISK, ASSUMPTION.

## 4. What the engine checks (the model never decides these)

- **Evidence (tightened 2026-11-19):** every repository citation is checked against the pinned snapshot: the quote must
  be in the cited lines **and** the cited lines must relate to the item (share a key term with its title or detail).
  **Verified**: at least one citation passes both. **Flagged**: a quote that is not in the cited lines. **Not traced
  to code**: no citation, or only citations that exist but are unrelated (those are dropped), so citing a file just
  because it exists never counts as evidence.
- **Checklist:** the agent receives the BRD statements it must reference, and a repository summary (file count and
  size). With little or no code it must design a new product: stack, project structure, schema, API contracts,
  deployment (prompt `arch.design` v2).
- **Traceability to the BRD:** `brdRefs` that do not name an item of the approved BRD are dropped. The engine then
  computes **coverage**: every BRD scenario, acceptance criterion, impacted component and non-functional point should
  be referenced by at least one design item. Uncovered BRD statements are listed on the design as **"Not covered by
  the design"**, so the reviewer sees gaps instead of trusting the model's completeness.
- **Content fingerprint:** each version has a SHA-256 over its canonical content; the approval binds to it.
- Versions are **immutable** once produced (database trigger); decisions are **append-only**.

## 5. Review and approval (GD-27)

- A **Technical Approver** reviews the design, accepts flagged items as they are, and **approves** it, after confirming
  in a dialog that they approve it alone. The database refuses an approval without that recorded confirmation.
- The **Requirement Author** or a Technical Approver can **request changes** with a note (10–5000 characters); the
  agent produces a new version with the note.
- Notifications (in-app and email, GD-25): BRD handed over for design, no Technical Approver (to Organisation Admins),
  design ready for review (Technical Approvers and the author), approved, failed.
- The approved design downloads as **PDF and Word**, with the BRD coverage table and the approval record.

## 6. Data model (migration 0044)

| Table | Purpose |
|---|---|
| `tdd_version` | One row per version: requirement, the BRD version it designs, status, summary, open questions, content hash, model, the change request that produced it, failure code |
| `tdd_item` | The design statements with section, kind, title, detail, component, BRD references, support state and verified evidence |
| `tdd_decision` | Append-only: FLAG_ACCEPTED, AUTHOR_CONFIRMED, APPROVED, CHANGES_REQUESTED |

The API cannot enqueue jobs: "start" and "request changes" publish `TddRequested`; the worker consumer enqueues
`TDD_GENERATION`.

## 7. Open points (defaults chosen; owner to confirm)

| # | Point | Default until decided |
|---|---|---|
| T-1 | Charging for the design | Included; no separate quote. Model cost is tracked per call as for the BRD |
| T-2 | Who approves the design | **Decided (GD-27):** the project's Technical Approvers; one may approve alone after explicit confirmation |
| T-3 | Included change requests for the design | Not limited (no charge) |
| T-4 | Design documents uploaded by the client | Not used yet; the design reads the BRD's documents only through the BRD |
