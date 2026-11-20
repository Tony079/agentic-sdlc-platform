# Garuda: Delivery plan stage

Status: **proposed** (2026-11-20), for owner review before implementation. Decision to record: GD-34.

An approved estimate moves the requirement to the next stage, **Delivery plan**. A Planning agent turns the approved
design and estimate into a backlog of epics and user stories; Garuda (never the model) checks that every estimate work
item is planned, keeps story effort consistent with the approved estimate, orders the work by its dependencies and lays
it out in sprints for the team. The plan is reviewed and approved like the earlier stages, then exported or pushed to
an issue tracker.

## 1. Lifecycle

| Step | Who | What happens |
|---|---|---|
| Handover | Engine (worker) | When an estimate is approved, the project's planners are notified (Organisation Admins when there are none). |
| Start | Planner | Sets sprint length (1 to 4 weeks), the first sprint's start date and the team's capacity per role (defaults from the approved estimate's team). Starts the plan. |
| Generate | Planning agent | Writes epics and stories from the approved design and estimate (live timeline and drafts, as for earlier stages). |
| Review | Planner | Edits a story's title, acceptance criteria, priority or sprint (with a reason), merges or splits stories, accepts flags, or asks for a revised version (which keeps everything not asked to change). |
| Approve | Approver (see 7.1) | Approves the exact version shown, alone with the explicit confirmation used for designs and estimates. |
| Export / push | Planner | CSV and PDF exports; optionally pushes epics and stories to the connected tracker (see 7.3). |

## 2. Inputs (frozen at start)

- The approved estimate: every work item (role, phase, low / likely / high days, dependencies, design references) and
  its settings (team, working days).
- The approved design (item keys and titles) and the approved BRD (acceptance criteria, scenarios), so stories carry
  testable acceptance criteria traced to the BRD.
- The plan settings from step "Start".

## 3. The Planning agent

Prompt `plan.backlog` v1. Output (strict JSON schema, validated, one repair):

```
{ "epics": [ { "title": "...", "goal": "...", "designRefs": ["<design item key>"] } ],
  "stories": [ {
      "epic": "<epic title>", "title": "As a <role>, I want ..., so that ...",
      "acceptanceCriteria": ["Given ... when ... then ..."],
      "estimateRefs": ["<estimate work item key>"], "brdRefs": ["<BRD item key>"],
      "role": "FRONTEND | BACKEND | ...", "priority": "MUST | SHOULD | COULD",
      "dependsOn": ["<title of another story>"] } ],
  "risks": ["..."] }
```

The agent never sets effort, sprint numbers or dates.

## 4. What the engine checks and computes

- **Coverage:** every work item of the approved estimate is planned in at least one story; gaps block approval until a
  planner accepts them. Unknown estimate, design or BRD references are dropped.
- **Effort:** a story's effort is the expected effort (PERT, adjustments included) of the estimate work items it
  carries, shared equally when a work item is split across several stories. The plan's total therefore equals the
  approved estimate's expected effort; the difference, if any, is shown.
- **Acceptance criteria:** every story has at least one; every BRD acceptance criterion is traced by at least one story,
  otherwise flagged.
- **Order and sprints:** stories are ordered by dependency (a cycle is broken and flagged) and priority, then packed
  into sprints by role capacity (people x working days x sprint length); a story larger than one sprint's capacity is
  flagged for splitting. Sprint dates follow from the start date and sprint length.
- **Edits:** a planner's change is recorded with a reason; sprints are recomputed; the original proposal is kept.

## 5. Review and approval

As for estimates: flags and gaps decided first; approval bound to a hash of the content and every edit; a revised
version keeps everything not asked to change.

## 6. Data model (new migration)

`plan_version`, `plan_epic`, `plan_story`, `plan_edit` (append-only), `plan_decision` (append-only),
`project_plan_defaults`, and for the tracker push `plan_push` (one row per pushed item: tracker, external key, URL,
status). Row-level security and guards as for estimates. Job `PLAN_GENERATION`, event `PlanRequested`, hand-over event
`EstimateApproved`, prompt `plan.backlog` v1, timeline stage `PLAN`.

## 7. Open points (owner to decide)

1. **Who plans and who approves.** Proposed: Estimators also plan (they know the work items); a BRD Approver approves.
   Alternatives: a new Planner role; or Technical Approvers plan.
2. **Who sees the plan.** Proposed: the whole project, once approved (stories are working material for the team);
   drafts only to planners and approvers. Alternative: same restriction as estimates.
3. **Issue tracker.** Proposed: CSV and PDF exports now, plus a push to **GitHub Issues** of the project's repository
   through the Garuda GitHub App (epics as milestones or labelled issues, stories as issues, acceptance criteria as
   checklists). This needs the GitHub App to be granted issue-write permission, a change from its read-only use today.
   Alternatives: Jira (needs a Jira site and API token per organisation); or exports only.
4. **Sprints.** Proposed: Garuda proposes the sprint layout from capacity; planners can move stories. Alternative: no
   sprints, a prioritised backlog only.
