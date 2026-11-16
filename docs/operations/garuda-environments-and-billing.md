# Environments and billing: operations

How to run project environments (GD-20, GD-21) and BRD billing (GD-22) in an installation.

## GitHub App (read-only)

1. Create a GitHub App owned by Garuda with **read-only** permissions: *Contents* and *Metadata*. It needs no webhooks and no write permission.
2. Mount its private key in the **worker** only: `SDLC_GITHUB_PRIVATE_KEY_FILE`, with `SDLC_GITHUB_APP_ID` and `SDLC_GITHUB_ENABLED=true` (see `secrets-and-key-management.md`). The API never holds GitHub credentials.
3. Each client installs the App on its GitHub organisation, on the repositories of its products.

When the App is not installed on a repository, its Environment Manager sees **App not installed** on the Environments tab. Ask the client's GitHub administrator to install it, then use **Check again**.

## Environments

- The Organisation Admin names one **Environment Manager** per project (Project › Members).
- The Environment Manager connects repositories (`owner/name` or URL), adds environments (for example DEV, QC, Pre-prod, PROD), and gives each a branch per repository.
- The worker job `ENV_VERIFY` checks every branch and records its head commit. Statuses: *Checking*, *Verified*, *Branch not found*, *App not installed*, *Unavailable*.
- **PROD:** marking an environment as PROD, and every later change to it, is a request. A BRD Approver of the project other than the requester approves it on the same tab. The database refuses PROD changes without an executed request.

## How requirements use environments (GD-23)

- A requirement on an existing product picks an environment (PROD is preselected). Submitting it pins every repository of that environment at its branch head; the worker fetches and indexes each one, then analysis starts (after the BRD quote is accepted, for billed projects).
- Submission is refused while the environment has no verified branch: the Environment Manager must add one first.
- The environment cannot be changed once the quote is accepted, and an environment that requirements were analysed against cannot be removed.
- Every 15 minutes the worker re-reads all branch heads. The requirement page then shows which repositories have moved since the analysis. After release, the requirement's owner can choose **Re-analyse with the latest code**.

## BRD review by the client (GD-24)

- The BRD appears on the requirement page for its **author** as soon as the analysis finishes, with the feasibility verdict.
- The author decides on every **flagged** statement (*Accept as is*, or *Request changes*), then **confirms**.
- A **BRD Approver** of the project (not the author) **approves**. The BRD is published to the project automatically when the automated checks pass. If a check blocks it, the author is notified.
- Three change requests are included; from the fourth, each one is charged at the BRD's size price after the client agrees. These appear in billing as `CHANGE_REQUEST_n` lines.
- A *Not feasible* verdict closes the requirement; its BRD shows why, and the quote is waived (`WAIVED_NOT_FEASIBLE` in billing).

## BRD quotes and billing

- Garuda Admins propose and approve plans (two people). A plan sets the monthly fee per active project, the BRDs included per month, and a price for Small, Medium and Large BRDs.
- A requirement in a project with a plan shows a **BRD quote** after it is submitted. Analysis starts only after its owner or a BRD Approver accepts the quote.
- **Garuda › Billing › Monthly report** lists project fees and BRD charges; **Download CSV** exports both (`line_type` is `PROJECT_FEE` or `BRD`).
- **Garuda › Billing › AI cost** shows model spend in USD, drilling down from organisation to project to requirement.
- Organisation Admins see their own charges under **Administration › Billing**.

## Checks after deployment

- Liquibase applied `0035-project-environments.sql`, `0036-brd-quotes-ai-cost.sql` and `0037-environment-sources.sql`.
- A test project: connect a repository and add a branch; it reaches *Verified* within a minute while the worker runs.
- Submit a test requirement in a project with a plan: the quote appears, and analysis waits until the quote is accepted.
