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
- **How requirements use them:** a requirement on an existing product chooses its **target environment**. Analysis reads that environment's branches at a recorded commit. In future releases, agents branch off it and open pull requests into it; they never push to PROD or protected branches.
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
