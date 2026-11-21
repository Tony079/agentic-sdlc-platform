# Garuda: Delivery plan stage

Status: **implemented**. Phase 1 (2026-11-20): the plan, its review, approval and exports. Phase 2 (2026-11-21): the
GitHub Issues push (7.3, section 9).

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
   (stories as issues with acceptance-criteria checklists; see section 9 for how epics and sprints map). The Garuda GitHub App must be granted
   issue-write permission for this; until it is, the push is unavailable and says why. Built after the plan itself.
4. **Sprints.** Garuda proposes sprints (1 to 4 weeks) with dates from the team's capacity; Estimators can move stories.

## 8. Implementation notes (phase 1)

- Migration `0056-delivery-plan.sql`: the tables, `app.sees_plan` (Estimators and BRD Approvers, or anyone in the
  project once the version is approved), guards, job `PLAN_GENERATION`, events `PlanRequested` and `EstimateApproved`,
  timeline stage `PLAN`, prompt `plan.backlog` v1.
- The worker may not read people's names, so it reads estimate adjustments without them (figures only).
- Effort per story: expected effort (PERT, adjustments included) of the estimate work items it carries, shared equally
  when a work item is split across stories. The plan total and the estimate total are shown side by side to insiders.
- Sprints: stories in dependency order (priority, then the agent's order, breaks ties; a cycle is broken and flagged),
  packed per role into sprints of people x working days x weeks; a story may share a sprint with what it depends on;
  an Estimator's pinned sprint is kept and flagged if it comes before a dependency; a story bigger than a sprint is
  flagged OVERSIZED and given a sprint of its own role.
- Visibility: before approval, only Estimators and BRD Approvers see the plan and its timeline; once approved, every
  project member sees epics, stories, sprints and risks (not the estimate's work items, gaps or edit history).

### 8.1 Changing sprint settings and bulk flag decisions (2026-11-21)

- An Estimator can **change the sprint settings** (sprint length, start date, working days, team) of a plan in review.
  Garuda supersedes the version and makes the next one with the same epics and stories (edits applied; a renamed
  story's dependants follow it; pinned sprints are dropped because sprint numbers change), without a model call
  (`plan_version.copied_from`, migration `0058`). Decisions on flags are made again, because the flags change with
  the sprints. `POST /api/v1/requirements/{id}/plan/settings {settings}`.
- **Keep all as proposed**: every undecided story carrying the same flag (for example "larger than one sprint") is
  accepted in one action, one recorded decision per story. `POST .../plan/flags/accept-all {flag}`.
- Why: with one person per role and one-week sprints each role has 5 days per sprint, so most stories were flagged
  as oversized and the settings could not be changed without starting over.

## 9. GitHub Issues push (phase 2, 2026-11-21)

- **Who:** an Estimator, on the approved plan, to a **verified repository of the project** (Environments tab). For a
  new product this is a new, empty code repository connected to the project. Anyone in the project sees the push
  status and each story's issue link once the plan is approved.
- **Mapping:** each story is an issue (description: epic and goal, sprint and dates, role, priority, expected effort,
  acceptance criteria as a checklist, dependencies as links to the pushed issues). Each sprint is a milestone
  `REQ-xxxxxx Sprint n` due on the sprint's last day: a GitHub issue has one milestone, and milestones carry dates, so
  sprints fit them better than epics. Epics, roles and priorities are labels (`epic: …`, `role: …`, `priority: …`),
  plus `garuda`.
- **Frozen content:** the API builds what is pushed from the approved, project-visible plan when the push is requested
  (`plan_push.payload`), so the worker never reads plan internals or people's names.
- **Idempotent:** every milestone and issue is recorded (`plan_push_item`) in its own transaction as soon as GitHub
  returns it. A retry of the same push continues where it stopped; a later push updates the recorded items. Title,
  labels, milestone and description are updated; criteria ticked on GitHub stay ticked; the issue's open or closed
  state is never changed. A recorded item deleted on GitHub is created again. Residual risk: if the worker stops
  between GitHub creating an item and Garuda recording it, the next attempt creates a duplicate.
- **Untrusted text:** plan text is made inert before it reaches GitHub: no mentions (`@name`), no references to other
  issues (`#12`), no HTML, no markdown structure from the model.
- **Credentials:** the worker's GitHub App broker asks for a token narrowed to the one repository with Issues: Read and
  write and Metadata: Read only (never Contents). If the installation has not accepted the Issues permission, the push
  fails with a message saying so and writes nothing. Operations steps: `docs/operations/garuda-environments-and-billing.md`.
- **Data model:** migration `0057-plan-push.sql`: `plan_push` (QUEUED → RUNNING → DONE / PARTIAL / FAILED; guard: an
  Estimator, an approved version, a verified project repository; one open push per requirement and repository) and
  `plan_push_item` (never deleted). Job `PLAN_PUSH`, event `PlanPushRequested`. Endpoint
  `POST /api/v1/requirements/{id}/plan/push {repositoryId}`; the plan state carries `push`.

