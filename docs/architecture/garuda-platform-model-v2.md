# Garuda Platform Model v2: Admins, Organisation Records, Billed Projects, Environments and Cost

| Item | Value |
|---|---|
| Status | **APPROVED by the owner on 2026-11-15** (decisions GD-16 to GD-22, decision register §16) |
| Extends | `garuda-platform-model.md` (GD-01 to GD-15). Where the two differ, this document wins. |
| Branch | `feature/garuda-platform` |

## 1. Who manages what

| Actor | Manages | Never manages |
|---|---|---|
| **Garuda Admin** (staff, `STAFF_ADMIN`) | Garuda staff; client organisations and their records; **Organisation Admins** (invite, disable, reactivate, reset authenticator); project approval and billing plans | The organisation's other users, or the content of requirements and BRDs |
| **Organisation Admin** (client) | Users of the organisation and their project roles; project requests; organisation-side settings | Other organisations; Garuda staff |
| **Environment Manager** (client, one per project) | Environments, repositories and branches of the project | Users and roles |

**The Garuda Admin role** is one role. `STAFF_ADMIN` includes onboarding (`ORG_ONBOARD`) and the dashboard (`AUDITOR`), so the two bootstrap administrators can onboard organisations without further grants.

**Every Garuda action needs two Garuda Admins:** one creates the request, the other approves it, and neither may be the person changed. The database enforces this.

## 2. Organisation record (GD-17)

Captured on the onboarding request and copied to the organisation when it is approved. Later changes are profile-change requests with the same two-person rule.

| Group | Fields | Pilot |
|---|---|---|
| Identity | Legal name, display name, sign-in code, industry, country, registered address, website | Legal name, display name and sign-in code are mandatory |
| Contacts | Primary Organisation Admin (invited); billing contact; technical contact; support email | Primary admin mandatory |
| Commercial | Contract reference, start date, renewal date, user limit | Optional |
| Security | **Allowed email domains**; data region; MFA policy (`ADMINS` or `ALL_USERS`) | Domains mandatory |
| Status | Active, Suspended, Deleted, with history | Existing |

**Allowed email domains** are enforced by the database on every invitation, whether by Garuda or by an Organisation Admin. A user whose email domain is not listed cannot be created.

## 3. Organisation Admins managed by Garuda (GD-16)

Garuda Admins can do each of these, each as a two-person **organisation admin request**:
- invite an additional Organisation Admin (email link; the person sets their own password);
- disable or reactivate an Organisation Admin;
- reset an Organisation Admin's authenticator.

Garuda never lists, creates or changes the organisation's other users. Organisation Admins keep managing their own users. Garuda's actions on an admin appear in the organisation's audit history.

## 4. Projects are approved and billed (GD-19)

```
Organisation Admin requests a project ──► PENDING
Garuda Admin A approves with a plan ───► APPROVED BY ONE
Garuda Admin B confirms ──────────────► ACTIVE   (billing starts: activated_at)
Organisation Admin archives ──────────► ARCHIVED (billing stops: archived_at; data stays readable)
```

- While a project is PENDING, nobody can add requirements, environments or repositories. The database enforces this.
- A **billing plan** is a Garuda record with these fields:
  - the monthly fee per active project;
  - included BRDs per month;
  - a price per BRD size class (Small, Medium, Large);
  - the currency.

  Plans are created and changed by Garuda Admins, with two people.

## 5. Project roles (GD-18)

| Role | Can do |
|---|---|
| **Requirement Author** | Creates requirements, uploads documents, answers clarifications, accepts a BRD quote for their own requirement |
| **BRD Approver** | Everything a Requirement Author does, plus approving BRDs, change decisions and PROD environment changes |
| **Viewer** | Read only |
| **Environment Manager** | One per project, named on the project (a member with any role). Registers environments, repositories and branches. |

Existing data migrates as follows: REQUESTER becomes REQUIREMENT_AUTHOR, and APPROVER becomes BRD_APPROVER.

## 6. Environments, repositories and branches (GD-20, GD-21)

```
Project
 ├─ Repositories  (GitHub, through the Garuda GitHub App installed by the client; read-only)
 └─ Environments  (defined per project; exactly one may be marked PROD)
      └─ Environment branch: repository + branch, with the verified commit
```

- **Who changes them:** the Environment Manager adds, renames and removes environments and their repository-plus-branch pairs.
- **PROD changes:** any change to the PROD environment is a request that a **BRD Approver of the project** (not the requester) approves.
- **Verification:** Garuda checks through the GitHub App that each repository and branch exists, and records the head commit. A missing branch marks the environment *Needs attention*.
- **How requirements use them (GD-23):** a requirement on an existing product names its **environment**, PROD by default. Analysis reads **every repository** of that environment at its branch head, pinned when the inputs are prepared; file paths are shown to the model as `owner/repository/path`, so each citation in the BRD names its repository and commit. The environment is fixed once the BRD quote is accepted. When branches move later, the requirement shows *the code has changed*; nothing is invalidated, and after release the author can **re-analyse** with the latest code (a new BRD version, no new charge). In future releases, agents branch off the environment and open pull requests into it; they never push to PROD or protected branches.
- **C-01 is reversed:** Garuda staff no longer register repositories. The per-requirement repository link flow (C-18) is replaced by project environments.
- **Scope:** this is configuration only. Agents acting on branches is a later release. Package names avoid the reserved later-stage names.

## 7. AI cost, BRD quotes and billing (GD-22)

**Cost tracking:** every model call already records its cost. Garuda adds totals per organisation, per project, per requirement and per month, shown on the dashboard and in the billing report.

**BRD quote (B with C):**
1. When a requirement is ready for analysis, a **quote** is prepared. It holds a size class (Small, Medium or Large) from the documents, the scope and the number of repositories, plus the plan's price for that class.
2. If the project's monthly allowance still covers the BRD, the quote shows *Included in plan*.
3. The Requirement Author or a BRD Approver **accepts** the quote. Only then does analysis start. A declined quote leaves the requirement waiting.
4. The actual AI cost is recorded against the requirement. The dashboard shows quoted price against actual cost, which is Garuda's margin.

**Billing report:** per organisation and month, it lists:
- active project-days × the monthly fee (prorated);
- included BRDs used;
- overage BRDs with their quoted prices;
- AI cost.

It can be exported as CSV. Invoices and payment collection are out of scope.

**Prices before a model provider is configured:** until a real model provider is configured (O-01), costs come from the configured price table, and the dashboard marks them *estimated*.

## 8. Delivery order

1. Garuda Admin role; Organisation Admins managed by Garuda (two-person).
2. Organisation record; allowed email domains enforced.
3. Project approval; billing plans.
4. Role rename; Environment Manager.
5. Environments with GitHub repositories and branches.
6. AI cost totals, BRD quotes, billing report.

Each step is tested and committed separately.

## 9. Implementation status (2026-11-15)

All six steps are implemented on `feature/garuda-platform`, each with integration tests; the full backend suite (67 test classes) and the frontend suite pass.

| Step | Migration | Where in the product |
|---|---|---|
| 1–2 GD-16/17 | 0032 | Garuda › Organisations (record, admins; two-person) |
| 3 GD-19 | 0033 | Garuda › Billing › Project approvals, Plans |
| 4 GD-18 | 0034 | Project › Members (roles, Environment Manager) |
| 5 GD-20/21 | 0035 | Project › Environments; Requirement › Target environment |
| 6 GD-22 | 0036 | Requirement › BRD quote; Garuda › Billing › Monthly report, AI cost; Administration › Billing |

How step 6 works as built:
- The quote opens when a requirement is **submitted** in a project with a plan, and is refreshed from the analysis inputs (documents, repository, text) until it is accepted. Projects without a plan (in use before billing) are not quoted.
- Sizing is **RULES_V1**: 2 points per document, 3 for an existing repository, 1 per 2,000 characters; Small ≤ 4, Medium ≤ 10, Large above. Every quote is labelled *estimated* until the BRD cost analysis agent is approved (O-01).
- On acceptance, the plan's included BRDs for that project and calendar month (UTC) are used first, then the size price. An accepted quote is final (database guard).
- AI cost comes from a daily rollup kept by a trigger on model-call records; the API login still cannot read raw model-call records. Calls without provider usage data add no cost and are counted separately.

**GD-23 (built 2026-11-15, migration 0037):** requirements analyse their environment, as described in section 6. Branch heads are refreshed every 15 minutes (`sdlc.worker.environment-refresh-interval`). The BRD size counts the repositories analysed (3 points each).

**GD-24 (built 2026-11-16, migration 0038):** BRDs go straight to the client. The BA agent (prompt `ba.phase-b` v3) returns a feasibility verdict and the GD-24 template sections. The requirement's author accepts each flagged statement (or requests changes), then confirms; a BRD Approver other than the author approves; the worker releases the BRD when the automated gates pass. Change requests 4 and later are charged at the BRD's size price (`brd_revision_charge`). A *Not feasible* verdict closes the requirement (T23 by the engine) and waives the quote. Workflow states keep their codes; screens show them as *BRD in review*, *Changes requested*, *Approved, publishing*, *BRD approved* and *Closed*.

**Open items:**
- **Quoted price against actual AI cost (margin)** is not shown: quotes are in the plan's currency and AI cost in USD, and no exchange-rate source has been decided.
- **Re-analysis during internal review** is done by the Garuda reviewer requesting a revision; that revision still uses the commits already pinned. Only the author's re-analysis after release fetches new heads.
- The BRD cost analysis agent (replacing RULES_V1) waits for O-01.
