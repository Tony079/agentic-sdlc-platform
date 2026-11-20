# Garuda: Delivery plan stage

Status: **decided** (owner, 2026-11-20), GD-34. Section 7 records the owner's decisions.

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

## 7. Owner decisions (2026-11-20)

1. **Roles.** Estimators plan (start, edit, accept flags, request revisions); a BRD Approver approves, alone only with
   the explicit confirmation. No new role.
2. **Visibility.** The approved plan is visible to the whole project (stories with effort in days, never money);
   drafts only to Estimators and BRD Approvers.
3. **Tracker.** CSV and PDF exports, plus a push to **GitHub Issues** of a repository registered to the project
   (stories as issues with acceptance-criteria checklists, epics as milestones). The Garuda GitHub App must be granted
   issue-write permission for this; until it is, the push is unavailable and says why. Built after the plan itself.
4. **Sprints.** Garuda proposes sprints (1 to 4 weeks) with dates from the team's capacity; Estimators can move stories.
