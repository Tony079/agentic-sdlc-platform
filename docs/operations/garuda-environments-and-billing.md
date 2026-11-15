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

## BRD quotes and billing

- Garuda Admins propose and approve plans (two people). A plan sets the monthly fee per active project, the BRDs included per month, and a price for Small, Medium and Large BRDs.
- A requirement in a project with a plan shows a **BRD quote** after it is submitted. Analysis starts only after its owner or a BRD Approver accepts the quote.
- **Garuda › Billing › Monthly report** lists project fees and BRD charges; **Download CSV** exports both (`line_type` is `PROJECT_FEE` or `BRD`).
- **Garuda › Billing › AI cost** shows model spend in USD, drilling down from organisation to project to requirement.
- Organisation Admins see their own charges under **Administration › Billing**.

## Checks after deployment

- Liquibase applied `0035-project-environments.sql` and `0036-brd-quotes-ai-cost.sql`.
- A test project: connect a repository and add a branch; it reaches *Verified* within a minute while the worker runs.
- Submit a test requirement in a project with a plan: the quote appears, and analysis waits until the quote is accepted.
