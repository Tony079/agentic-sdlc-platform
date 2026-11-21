# Garuda: Agent delivery mode (GD-35)

Status: **implemented** (2026-11-21).

Coding agents write most of the code; what takes time is people reviewing and accepting it, and work outside the
code that waits on people or suppliers. Sprints sized for human developers (person-days, people per role) are the
wrong model for that, so the estimate and the delivery plan have a delivery mode.

| | Human team (`HUMAN`) | Coding agents, reviewed by people (`AGENT`, default in the form) |
|---|---|---|
| Work item | Person-days of development (low / likely / high) | One agent task (one pull request): agent hours, plus the reviewer's person-days (low / likely / high) |
| Outside work | Not separated | `EXTERNAL` items (OAuth registrations, provider accounts, approvals, policy sign-off) with a calendar lead time |
| Team in the settings | People per role | Reviewers per role, plus how many coding agents work at once |
| Effort shown | Person-days | People's review person-days, and agent hours |
| Cost | Day rates x person-days | Reviewers' day rates x review days, plus an optional AI cost per agent hour |
| Plan | Sprints | Waves; outside work first |

## Estimate

- Prompt `est.items` v2 (the human mode keeps v1). The agent proposes, per item: `kind` (WORK or EXTERNAL),
  `agentHours`, review low / likely / high days, `leadDays` for outside work. Garuda clips them (review 0.1 to 30
  days, agent hours 0 to 200, lead 0 to 180) and flags CLIPPED, UNCERTAIN (review high above 5 x low) and LARGE (more
  than 16 agent hours: split it).
- The review days are stored in the existing low / likely / high columns, so PERT, adjustments, approvals and the plan's
  effort work unchanged. New columns: `estimate_item.kind`, `agent_hours`, `lead_days` (migration `0059`).
- Timeline: the longest of (a) the dependency chain, each item taking its agent hours (8 per working day) plus its
  review, and outside work its lead time; (b) the reviewers' load per role; (c) the agents' load (hours / agents at
  once). The summary also gives agent hours, the number of waves, the outside items and the longest wait.
- Switching: an approved estimate can be started again in agent mode; one in review offers "Re-estimate for coding
  agents" (a revision with the new settings).

## Delivery plan

- The plan follows its estimate's mode. Each story carries the agent hours and review days of its estimate work
  items, and is outside work when all of them are (`plan_story.agent_hours`, `external`, `lead_days`).
- Waves: wave n holds the stories whose dependencies are all in earlier waves. A wave starts when the previous one is
  reviewed and the outside work its stories depend on has arrived; it takes the agents' build time (up to the
  configured number at once, at least the longest story) plus the review time per role, shared among its reviewers.
  Wave 0 is outside work, started at once. Dates skip weekends for a 5-day week.
- A story with more than 16 agent hours is flagged as too big for one agent task.
- GitHub push: one milestone per wave (`REQ-xxxxxx Wave n`, and `Outside work`), labels `agent-ready` or
  `outside-work`, and an issue description that briefs the coding agent: one pull request referencing the issue, with
  tests for each acceptance criterion; a person reviews it before merge.

## Human mode correction (same change)

Sprint scheduling now treats each story as one person's work that starts only after the stories it depends on are
finished, and keeps a story that fits in a sprint inside one sprint. "Larger than one sprint" compares a story with
one person's sprint, not the whole team's. Before, a large team made a whole chain of dependent stories fit in one
sprint.
