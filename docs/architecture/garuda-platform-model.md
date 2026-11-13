# Garuda Platform Model: Organisations, Projects, Users and Roles

| | |
|---|---|
| Status | **APPROVED by the owner on 2026-11-13** (decision register §15), with two changes to the recommendations: GD-04 invitations by Gmail email, GD-07 titles shown on the dashboard. PRD changes: `docs/product/PRD-v0.8-delta.md` |
| Product name | **Garuda** (the platform previously called "Agentic SDLC Platform") |
| Branch | `feature/garuda-platform`, from `cc05322` |
| Authorities | PRD v0.7 (frozen), TDD v1.0.4, `docs/decisions/decision-register.md` |
| Decisions | §11; recorded as approved in the decision register §15 |

## 1. Purpose

Garuda serves many client organisations. Each organisation has its own projects (products or entities), repositories, users and requirements. This document defines:
- the hierarchy;
- who the users are and how they are created;
- the roles and what each may do;
- what each role can see;
- what the Garuda admin dashboard shows;
- the onboarding and sign-in flows.

It is a model, not an implementation plan in code. §10 orders the work.

## 2. Scope change this requires

| Authority | Today | This model |
|---|---|---|
| PRD v0.7 §10 "Explicitly excluded (later releases)" | "Payment gateway, **multi-tenant onboarding UI** and SSO" are excluded. (§4 does give the Platform Admin the duty to "manage users, tenants", without screens) | Organisation onboarding and user administration screens **are in scope** |
| PRD v0.7 §4 roles | Client User, Client Approver, Business Reviewer, Auditor, Platform Admin; roles apply to the whole organisation | Roles are assigned **per project**, and an Organisation Admin role is added |
| Decision C-18 | A requirement is visible only to its owner (plus staff) | Visibility follows project membership (§6, decision GD-05) |

These are product changes. They need an explicit owner decision (GD-01), recorded as a PRD v0.8 delta and a decision-register entry, before any code is written.

## 3. Hierarchy

```
Garuda (the platform, operated by Garuda staff)
└── Organisation            (a client; today: app.tenant)
    ├── Organisation users  (today: app.app_user)
    └── Project             (a product or entity; today: app.project)
        ├── Project members (user + project role)      NEW
        ├── Repositories    (today: app.repository, registered per project)
        └── Requirements    (today: app.requirement)
            └── documents, clarifications, BRD versions, approvals, releases, audit
```

Rules:
- **Organisation** is the tenant: the isolation boundary. Forced row-level security stays exactly as it is. No data crosses organisations.
- **Project** is the unit of work and of access. Every requirement, repository and document belongs to exactly one project.
- One GitHub repository belongs to at most one organisation (existing rule, D-M4R-13).

## 4. Two kinds of users

| | Garuda staff | Organisation users |
|---|---|---|
| Who | Employees of the Garuda operator | Employees of a client |
| Table today | `staff_user` (global) | `app_user` (belongs to one organisation) |
| Sign-in | `/staff/login`, password and TOTP (mandatory, O-11) | Organisation sign-in (§8.4), password; TOTP for Organisation Admins (GD-08) |
| Reaches an organisation through | An assignment to that organisation, approved by two staff (existing) | Membership of that organisation |
| Can see client content by default | **No** (PRD FR-AUTH-14) | Yes, within their projects and roles |

The two realms stay separate: one person never uses one account for both. A staff member who also works for a client gets two accounts. Separation-of-duties rules compare people, not accounts, so this needs an identity link (GD-12).

## 5. Roles

### 5.1 Garuda staff roles

Staff capabilities already exist and are granted only by two administrators. This model adds one capability and names the rest for the dashboard.

| Role | Capability | Does |
|---|---|---|
| Garuda Platform Admin | `STAFF_ADMIN` (exists) | Grants and revokes staff capabilities and organisation assignments (two-person) |
| **Garuda Onboarding Manager** | `ORG_ONBOARD` (**new**) | Creates organisations, invites each organisation's first Organisation Admin, suspends or reactivates organisations (two-person, §8.1) |
| Business Analyst | `BUSINESS_ANALYST` (exists) | Assesses change requests |
| Business Reviewer | `BUSINESS_REVIEWER` (exists) | Reviews and approves BRDs internally; answers reviewer clarifications |
| Release Manager | `RELEASE_APPROVE` (exists) | Releases an approved BRD to the client |
| Repository Registrar | `REPOSITORY_REGISTER` (exists) | Registers a repository a client authorised |
| Operations | `JOB_RETRY`, `FEATURE_FLAG_ACK`, `TENANT_DELETE` (exist) | Retries, flags, organisation deletion (two-person) |
| Auditor | `AUDITOR` (**new**, PRD §4) | Read-only access to audit trails of assigned organisations |

Delivery roles (analyst, reviewer, release) work only in organisations they are assigned to. GD-09 decides whether assignment should go down to individual projects.

### 5.2 Organisation roles

Two levels: one organisation-wide administrative role, and project roles.

| Role | Level | Can do | Cannot do |
|---|---|---|---|
| **Organisation Admin** | Organisation | Create and archive projects; invite, deactivate and reactivate users; assign project roles; authorise repositories for any project (C-18 link flow); view the organisation's audit history; request organisation deletion (existing, two-person on the Garuda side) | See requirement content of a project without being its member (GD-06); approve anything as admin alone; remove the last Organisation Admin |
| **Requester** | Project | Create, edit, submit and cancel requirements; upload documents; answer clarifications assigned to them; submit change requests; see released BRDs of their requirements | Approve BRDs or change requests |
| **Approver** | Project | Everything a Requester sees in that project; accept or decline change requests; authorise repositories for the project; approve client-side decisions | Approve a requirement or change request they submitted (FR-AUTH-16) |
| **Viewer** | Project | Read requirements, released BRDs and status in the project | Create or change anything |

Today's roles map onto this model:
- `CLIENT_USER` becomes Requester in each project they own requirements in.
- `CLIENT_APPROVER` becomes Approver in every project of the organisation.
- The first Approver of each existing organisation also becomes Organisation Admin, recorded as a data migration.

Later stages (estimation, code review, testing, deployment) add project roles such as "Code Reviewer". **They are outside Release 1** (PRD §1, stages 1–2) and are not defined here.

### 5.3 Rules for every role

1. Roles are additive. A user can hold different roles in different projects; within a project, Approver includes Viewer rights.
2. **Nobody approves their own work.** Enforced by the database, as today for BRD approval, release and change requests.
3. Only an Organisation Admin assigns organisation roles. Only Garuda staff, two people, assign staff roles. No role can grant itself.
4. Every grant, change and removal is audited with who, whom, which role, which project and when, never with content.
5. A model or agent never holds a role and never takes an authorisation decision (FR-AUTH-05).

## 6. What each user can see

| Content | Requester | Approver | Viewer | Organisation Admin (not a member) | Garuda staff |
|---|---|---|---|---|---|
| Project list | Own projects | Own projects | Own projects | All projects in the organisation (names, members, counts) | Assigned organisations: names and counts only |
| Requirement content | See GD-05 | All in the project | All in the project | **No** (GD-06) | Delivery roles in assigned organisations, for the work they do (existing) |
| Documents | Same as requirement | Same | Same | No | Business Reviewer during review (existing) |
| Released BRD | Own requirements (GD-05) | All in the project | All in the project | No | Delivery roles |
| Internal BRD versions | No | No | No | No | Business Reviewer, Release Manager |
| Audit history | Own requirements | Project | No | Whole organisation (content-free) | Auditor, for assigned organisations |

**C-18 today:** only the requirement's owner sees it, because clients asked for that confidentiality. GD-05 decides whether, with projects, the default becomes "all members of the project". An option is for a requirement to be marked restricted (owner and Approvers only).

## 7. The Garuda admin dashboard

The dashboard is for Garuda staff. Per FR-AUTH-14 it shows **metadata, never content**.

| Shows | Does not show |
|---|---|
| Organisations: status, created date, number of users, projects, repositories | Requirement descriptions or other text (titles **are** shown, GD-07) |
| Per organisation: projects (name, status), user count by role, last activity | Documents, extracted text, source code |
| Requirements by **state** and **age** per organisation and project, with **titles** (GD-07) | BRD content, clarification text, answers |
| Bottlenecks: waiting for clarification longer than N days, failed analyses, BRDs waiting for review or release | User email addresses of client users (GD-07) |
| Delivery workload: what each Garuda reviewer has open | Anything from an unassigned organisation in detail (counts only) |
| Security: alerts, locked accounts, pending invitations, MFA enrolment of Organisation Admins | |
| Onboarding actions: create organisation, invite first admin, suspend or reactivate | |

Viewing content of a client goes through the existing delivery roles (assignment to that organisation). Broader access needs the time-limited, reasoned, audited grant of open decision O-32.

## 8. Flows

### 8.1 Onboarding an organisation (Garuda)

1. An Onboarding Manager enters the organisation's name and sign-in key (the "slug", for example `acme`), and the first admin's name and email.
2. **A second Onboarding Manager approves.** The organisation is created ACTIVE, and the first admin receives an invitation (§8.3).
3. Until that admin accepts, the organisation shows "Waiting for admin" on the dashboard. The invitation can be re-sent or revoked.
4. Everything is audited in the platform audit trail.

Whether the second approval is required is GD-03. It follows the existing two-person pattern for staff grants and tenant deletion.

### 8.2 Inside the organisation (Organisation Admin)

1. Creates projects: name, description, optional key prefix.
2. Invites users by email, choosing for each a project and a role.
3. Changes roles, removes members from projects, deactivates users, re-sends invitations.
4. A user removed from every project keeps their account but sees nothing. A deactivated user cannot sign in, and their sessions end at once.
5. There must always be at least one active Organisation Admin.

### 8.3 Invitation

1. The invitation is a single-use link holding a random token (only its hash is stored). It expires after 72 hours (the TDD default; GD-10).
2. The invitee opens it, sets their own password (and TOTP if Organisation Admin), and becomes ACTIVE. Nobody else ever knows the password.
3. Expired or revoked links show a neutral message.
4. **Email delivery (GD-04):** Garuda emails the link through SMTP (Gmail with an App Password, or any SMTP service), and the admin can always copy it. Without SMTP configured, copy-link is the only path. AC-EXT-40 is changed for invitation and reset email only.
5. Password reset uses the same one-time-link mechanism, started by the user or an Organisation Admin.

### 8.4 Sign-in

Today the client sign-in asks for organisation, email and password, because the same email may exist in two organisations. Proposed:
- each organisation has its own sign-in address, `/o/{slug}/login`, which every invitation links to;
- the generic `/login` asks only for the organisation key, then continues there.

This does not reveal which organisations an email belongs to (that would leak client relationships). See GD-11.

### 8.5 Organisation lifecycle

- **ACTIVE**, then **SUSPENDED** (sign-in blocked, data kept; two-person; reversible).
- **DELETED** (soft deletion, already implemented; two-person).
- Physical purge stays out of scope (open decision G-08).

## 9. Security requirements

1. Organisation isolation stays where it is enforced today: forced RLS, tenant context from the session only, 404 for other organisations' data.
2. **Project access is checked on every request** in the service layer. The database also refuses rows of projects the user is not a member of, through RLS on `project_id` (GD-13). Defence in depth, as for tenants.
3. Organisation Admins need MFA (GD-08); their actions are audited and alerted (new alert rules: admin created, role changed, last admin removal refused).
4. Invitation and reset tokens: 256-bit random, hashed at rest, single-use, expiring. Attempts are throttled.
5. Garuda staff keep their existing protections: MFA, two-person grants, assignment-gated access, no default content access.
6. Runtime database logins still cannot grant roles (S-02): role assignment goes through narrow, audited paths.

## 10. Delivery order (after the decisions)

| Step | Delivers | Depends on |
|---|---|---|
| 1 | PRD v0.8 delta and decision records for GD-01 to GD-14 | Owner decisions |
| 2 | Data model: project membership and roles, invitations, Organisation Admin, organisation onboarding records; migration of existing users | 1 |
| 3 | Garuda admin: onboard organisation (two-person), invite first admin, organisation list and detail (metadata) | 2 |
| 4 | Organisation Admin: projects, users, invitations, project roles | 2 |
| 5 | Project-scoped access everywhere: requirement, document, BRD and change-request endpoints check membership and role; RLS by project | 2 |
| 6 | Navigation and screens per role; organisation sign-in address | 4, 5 |
| 7 | Garuda dashboard metrics: states, ageing, bottlenecks, workload | 3, 5 |
| 8 | UI redesign under the Garuda name | 6 |

Each step ends with tests, including cross-organisation and cross-project access tests, before the next starts.

## 11. Decisions (approved 2026-11-13; all recommendations accepted except where marked)

| ID | Decision | Recommendation |
|---|---|---|
| **GD-01** | Bring organisation onboarding and user administration into Release 1 scope (PRD v0.8) | Yes |
| **GD-02** | Roles per project rather than per organisation | Yes |
| **GD-03** | Creating an organisation needs a second Garuda approver | Yes, consistent with other platform-level actions |
| **GD-04** | Invitations: copy-link only (no email), or Garuda sends email (changes AC-EXT-40) | Copy-link now; email when an email service is chosen. **Owner decision: email through Gmail SMTP, plus copy-link** |
| **GD-05** | Requirement visibility in a project: all members (Requester, Approver, Viewer), or owner and Approvers only, or per-requirement "restricted" flag | All members by default, plus an optional restricted flag |
| **GD-06** | May an Organisation Admin see content of projects they are not a member of? | No; they can add themselves as a member, which is audited |
| **GD-07** | May the Garuda dashboard show requirement titles and client user emails? | Titles: no, by default. Emails: only of Organisation Admins. **Owner decision: titles shown; emails only of Organisation Admins** |
| **GD-08** | MFA for Organisation Admins (mandatory) and other client users (optional) | Mandatory for admins, optional for others |
| **GD-09** | Garuda delivery staff assigned per organisation (today) or per project | Per organisation now; per project later if needed |
| **GD-10** | Invitation lifetime | 72 hours (TDD default) |
| **GD-11** | Organisation sign-in address `/o/{slug}/login` | Yes |
| **GD-12** | Link a person's staff and client accounts for separation of duties | Yes, link by verified email |
| **GD-13** | Enforce project membership in the database (RLS) as well as in the application | Yes |
| **GD-14** | Product renaming to Garuda in code (packages, images) or only in the UI and documents | UI and documents now; code names unchanged (renaming packages has no user value and touches every file) |

## 12. Out of scope for this model

- Self-service sign-up of new organisations (Garuda staff onboard every client).
- SSO (PRD: later).
- Billing, plans and quotas.
- Roles for later SDLC stages (estimation, code review, testing, deployment).
- Physical deletion of organisations.
