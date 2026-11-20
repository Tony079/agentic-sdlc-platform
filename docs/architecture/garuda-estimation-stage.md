# Garuda: Estimation stage

Status: **implemented** (2026-11-20), GD-33. Section 7 records the owner's decisions; section 8 the implementation notes.

An approved technical design moves the requirement to the next stage, **Estimation**. An Estimation agent breaks
the approved design into work items and proposes an effort range for each; Garuda (never the model) checks coverage
and computes every total, timeline and cost. The project's reviewers adjust and approve the estimate, as they do the
BRD and the design.

## 1. Lifecycle

| Step | Who | What happens |
|---|---|---|
| Handover | Engine | When a design is approved, the requirement is handed to the project's estimators (in-app and email notification). |
| Start | Estimator | Starts the estimate. Optional inputs first: team (roles and how many of each), working days per week, contingency %, and day rates if cost is wanted. Project defaults are offered. |
| Generate | Estimation agent | Writes work items from the approved design (live timeline and drafts, as for the BRD and design). |
| Review | Estimator | Reads items, adjusts any effort with a reason, accepts flagged items, may request a new version with a note (a revision keeps everything not asked to change). |
| Approve | Approver (see 7.1) | Approves the exact version shown; approving alone needs the explicit confirmation used for designs. |
| Export | Project members | PDF, Word and a spreadsheet (one row per work item). |

A new design version (approved later) makes the estimate stale: it is labelled "based on design version N" and a new
estimate is offered; the old one stays readable.

## 2. Inputs (all frozen at start)

- The approved design version: every item (section, kind, title, detail, component) and its BRD references.
- The approved BRD version (acceptance criteria and non-functional points drive test and hardening effort).
- The requirement (constraints, integrations) and answered clarifications.
- The estimate settings from step "Start" (team, calendar, contingency, optional rates).

No code is read: the design already states what changes. For an existing product the design's code references are
carried into the work items for context.

## 3. The Estimation agent

Prompt `est.items` v1. Output (strict JSON schema, validated, one repair as for other agents):

```
{ "items": [ {
    "title": "...", "detail": "what is done, in one or two sentences",
    "role": "FRONTEND | BACKEND | DATA | DEVOPS | QA | DESIGN | OTHER",
    "phase": "SETUP | BUILD | TEST | HARDENING | RELEASE",
    "designRefs": ["<design item key>"],
    "lowDays": 1.0, "likelyDays": 2.0, "highDays": 4.0,
    "assumptions": ["..."], "risk": "... or null",
    "dependsOn": ["<title of another item>"] } ],
  "assumptions": ["estimate-wide assumption"],
  "excluded": ["work the design does not cover and is not estimated"] }
```

Rules given to the agent: one item per unit of work a developer would pick up (usually 0.5 to 10 days); every design
item covered by at least one work item; test work for every BRD acceptance criterion; effort in person-days for one
competent developer of that role; never totals, timelines or money (Garuda computes them).

## 4. What the engine checks and computes (the model never decides these)

- **Coverage:** every design item is referenced by at least one work item; uncovered design items are listed and block
  approval until an estimator accepts the gap or requests a new version.
- **Sanity:** `low <= likely <= high`, all within 0.25 to 60 days; anything outside is clipped and **flagged**.
- **Uncertainty flags:** high more than 3 times low, or more than 15 days likely, is flagged for an estimator to accept
  or adjust (an item that large should usually be split).
- **Expected effort per item:** PERT `(low + 4 x likely + high) / 6`, with spread `(high - low) / 6`.
- **Totals:** by role, phase and overall, as expected effort and as a range (P50 and P85 from the summed PERT spreads),
  plus contingency %.
- **Timeline:** for the team in the settings, per role: effort divided by that role's people and working days, with a
  dependency-aware critical path over `dependsOn`; shown in weeks, as a range.
- **Cost (only when day rates are set):** effort x rate per role, plus contingency; shown with the currency of the
  project's billing plan.
- **Adjustments:** an estimator may change low/likely/high of any item with a reason (10 to 1000 characters); the
  original model figures and every adjustment are kept and shown ("adjusted by …").

## 5. Review and approval

- Flagged and uncovered items must be accepted or adjusted before approval (as flagged BRD statements).
- Approval binds the exact content (hash of items, adjustments and settings).
- Request changes: a new version revises the previous one (same rule as GD-32), with the note.
- Viewers see the approved estimate only; drafts and adjustment history are for estimators and approvers.

## 6. Data model (new migration)

- `estimate_version` (requirement, design version, version number, status GENERATING / IN_REVIEW / APPROVED /
  SUPERSEDED / FAILED, settings JSON, content hash, model, change request).
- `estimate_item` (version, ordinal, key, title, detail, role, phase, design refs, low / likely / high as proposed,
  assumptions, risk, depends on, flags).
- `estimate_adjustment` (append-only: item, new low / likely / high, reason, who, when).
- `estimate_decision` (append-only: accept flag, approve, request changes, with notes).
- `project_estimate_defaults` (team roles and sizes, working days, contingency, optional day rates and currency).
- RLS and project scoping as for designs; worker may write items, the API writes decisions and adjustments.

Job `ESTIMATE_GENERATION` (lease 300 s, runtime 30 min, 3 attempts), events `EstimateRequested`, prompt
`est.items` v1, timeline stage `ESTIMATE`.

## 7. Owner decisions (2026-11-20)

1. **Roles.** A new project role **Estimator** starts, reviews and adjusts the estimate; a **BRD Approver** approves it.
   One person may hold both roles; approving alone needs the confirmation used for designs.
2. **Money.** Effort and timeline always; cost only when the project sets a day rate per role.
3. **Adjustments.** Estimators may change any item's figures with a reason; the original figures and every
   adjustment are kept and shown.
4. **Visibility.** Only the project's Estimators and BRD Approvers see the estimate, drafts and the approved version
   alike, and only they can export it. Other members (including the requirement's author, unless they hold one of
   these roles) see that an estimate exists and its status, not its content.

## 8. Implementation notes

- Migration `0055-estimation.sql`: role `ESTIMATOR` (also sees restricted requirements), `app.is_estimator`,
  `app.sees_estimate`, the five tables with guards and row-level security, job `ESTIMATE_GENERATION`, events
  `EstimateRequested` and `TddApproved`, timeline stage `ESTIMATE`, prompt `est.items` v1.
- A member's session sees only its own project membership, so the hand-over to Estimators after design approval is
  done by the worker on `TddApproved` (Estimators notified, or Organisation Admins when there are none).
- Approval binds a review hash of the produced content plus every adjustment in effect; a change after the approver
  opened the estimate is refused (`VERSION_CHANGED`).
- An adjusted flagged item counts as decided; "Keep as proposed" records a `FLAG_ACCEPTED` decision.
- Exports: PDF and Word through the shared document renderer, and CSV (UTF-8 with byte-order mark; a cell starting
  with `=`, `+`, `-`, `@` is prefixed with `'` so a spreadsheet never evaluates it).
- Timeline: for each role, effort / (people x working days); the dependency chain (expected effort along
  `dependsOn`) sets a floor. A role with work but nobody in the team is assumed to have one person, and said so.
