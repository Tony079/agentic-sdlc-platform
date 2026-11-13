# PRD v0.8 Delta: Garuda Platform Onboarding and Roles

| | |
|---|---|
| Base | PRD v0.7 (frozen; not edited) |
| Date | 2026-11-13 |
| Decisions | GD-01 to GD-14, `docs/decisions/decision-register.md` §15 |
| Model | `docs/architecture/garuda-platform-model.md` |

This delta lists every change to PRD v0.7. Where it conflicts with v0.7, this delta wins. Everything not listed here is unchanged.

## 1. Name

The product is **Garuda**.

## 2. Scope (changes §10)

**Moved into Release 1** (out of "Explicitly excluded"):
- **Organisation onboarding** by Garuda staff, with two-person approval.
- **User administration** by Organisation Admins: invite, deactivate, reactivate, assign project roles.

**Still excluded:**
- self-service organisation sign-up;
- payment gateway;
- SSO.

## 3. Roles (changes §4)

| Role | Level | Replaces / adds |
|---|---|---|
| Garuda Platform Admin | Platform | Platform Admin (unchanged duties) |
| Garuda Onboarding Manager | Platform | **New**: creates and suspends organisations; invites the first Organisation Admin |
| Business Analyst, Business Reviewer, Release Manager, Repository Registrar | Platform, per assigned organisation | Unchanged |
| Auditor | Platform, per assigned organisation | Unchanged duty (FR-AUD-04) |
| **Organisation Admin** | Organisation | **New** |
| **Requester** | Project | Replaces Client User |
| **Approver** | Project | Replaces Client Approver; now per project |
| **Viewer** | Project | **New**: read-only |

## 4. New and changed requirements

| ID | Requirement | Priority |
|---|---|---|
| FR-ORG-01 | Garuda staff with `ORG_ONBOARD` create an organisation (name, sign-in key, first admin). A second, different staff member with `ORG_ONBOARD` approves it before it exists | P0 |
| FR-ORG-02 | The first Organisation Admin receives an invitation; the organisation shows "Waiting for admin" until it is accepted | P0 |
| FR-ORG-03 | Garuda staff can suspend and reactivate an organisation (two-person). Suspension blocks sign-in and keeps data | P0 |
| FR-ORG-04 | An Organisation Admin creates and archives projects | P0 |
| FR-ORG-05 | An Organisation Admin invites users by email, with a project and a role | P0 |
| FR-ORG-06 | An Organisation Admin changes project roles, removes members, deactivates and reactivates users. At least one active Organisation Admin always remains | P0 |
| FR-ORG-07 | Invitation and password-reset links are single-use, expire after 72 hours, and are stored only as hashes | P0 |
| FR-ORG-08 | Invitations and password resets are sent by email when SMTP is configured, and can always be copied by the admin | P0 |
| FR-ORG-09 | Each organisation has its own sign-in address. The generic sign-in asks for the organisation key and does not reveal which organisations an email belongs to | P0 |
| FR-ORG-10 | Organisation Admins must use TOTP MFA | P0 |
| FR-ORG-11 | Every organisation, user, role and invitation change is audited without content | P0 |
| FR-ACC-01 | Access to requirements, documents, BRDs, clarifications and change requests requires membership of the requirement's project, with the role the action needs | P0 |
| FR-ACC-02 | All members of a project see its requirements. A requirement marked **restricted** is visible only to its owner and the project's Approvers | P0 |
| FR-ACC-03 | An Organisation Admin who is not a member of a project sees its name, members and counts, not its content | P0 |
| FR-ACC-04 | Project membership is enforced by the application and by the database | P0 |
| FR-DSH-01 | The Garuda admin dashboard lists organisations, their projects, user counts by role, and requirements by state and age, **with titles** | P0 |
| FR-DSH-02 | The dashboard shows bottlenecks (old clarifications, failed analyses, BRDs waiting for review or release) and reviewer workload | P1 |
| FR-DSH-03 | The dashboard never shows requirement descriptions, documents, code, BRD content, clarification text or client user emails other than Organisation Admins' | P0 |
| FR-UI-10 | The interface meets an enterprise standard: consistent design system, accessible (WCAG 2.1 AA), responsive, with loading, empty, error and success states on every screen | P0 |

## 5. Changed acceptance criteria

| AC | Change |
|---|---|
| AC-EXT-40 | Release 1 sends in-app notifications only, **except** invitation and password-reset email (FR-ORG-08) and operator security alerts (C-17) |
| AC-DOC-20 | "Submitter cannot approve" now applies per project: a member who submitted a requirement or change request cannot approve it, whatever their role |

## 6. New acceptance criteria

| AC | Criterion |
|---|---|
| AC-GAR-01 | A Garuda staff member creates an organisation; it exists only after a second staff member approves; both actions are audited |
| AC-GAR-02 | The first admin accepts the invitation, sets a password and enrols TOTP; the invitation link cannot be used again |
| AC-GAR-03 | An Organisation Admin invites a user to a project with a role; the user sees only that project and only the actions of that role |
| AC-GAR-04 | A user of one project cannot read or change another project's requirements, through the API or directly in the database (404, recorded) |
| AC-GAR-05 | A restricted requirement is hidden from Requesters and Viewers other than its owner |
| AC-GAR-06 | The last active Organisation Admin cannot be removed or deactivated |
| AC-GAR-07 | The Garuda dashboard shows organisations, projects, users by role and requirements by state with titles, and no description, document, code or BRD content |
| AC-GAR-08 | Suspending an organisation ends its users' sessions and blocks sign-in; reactivating restores access without data loss |
