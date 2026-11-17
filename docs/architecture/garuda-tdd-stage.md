# Garuda: Technical design (TDD) stage

Decision GD-26 (owner, 2026-11-17): an approved BRD moves the requirement to the next stage, **Technical design**.
An **Architect agent** reads the approved BRD and the analysed code and writes a technical design document, which
the project reviews and approves in the same way as the BRD. Estimation follows in a later stage.

## 1. Lifecycle

```
BRD approved ──(author or BRD Approver: "Start technical design")──► GENERATING
GENERATING ──(agent output verified)──► IN_REVIEW ──(author confirms)──► AWAITING_APPROVAL
AWAITING_APPROVAL ──(BRD Approver, not the author, approves)──► APPROVED
IN_REVIEW / AWAITING_APPROVAL ──(author or approver requests changes)──► new version GENERATING (old: SUPERSEDED)
GENERATING ──(agent failed after retries)──► FAILED ──(author retries)──► GENERATING
```

- **Started explicitly, not automatically.** Each run costs model tokens, so the author or a BRD Approver starts it
  from the approved BRD. Only one design run can be in progress per requirement.
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

- **Evidence:** every repository citation is verified against the pinned snapshot exactly as for the BRD. An item
  whose citations all fail is **flagged**; an item with no citation is shown as **not traced to code**.
- **Traceability to the BRD:** `brdRefs` that do not name an item of the approved BRD are dropped. The engine then
  computes **coverage**: every BRD scenario, acceptance criterion, impacted component and non-functional point should
  be referenced by at least one design item. Uncovered BRD statements are listed on the design as **"Not covered by
  the design"**, so the reviewer sees gaps instead of trusting the model's completeness.
- **Content fingerprint:** each version has a SHA-256 over its canonical content; the approval binds to it.
- Versions are **immutable** once produced (database trigger); decisions are **append-only**.

## 5. Review and approval

Same roles and rules as the BRD (GD-24), without Garuda staff:

- The **Requirement Author** reviews, accepts flagged items as they are, and **confirms**, or requests changes.
- A **BRD Approver** of the project who is **not the author** gives the **final approval**.
- Either can **request changes** with a note (10–5000 characters); the agent produces a new version with the note.
- Notifications (in-app and email, GD-25): design ready for review, waiting for approval, approved, failed.
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
| T-2 | Who approves the design | The project's BRD Approvers. A separate "Technical Approver" role can be added later |
| T-3 | Included change requests for the design | Not limited (no charge) |
| T-4 | Design documents uploaded by the client | Not used yet; the design reads the BRD's documents only through the BRD |
