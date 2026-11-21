# Environments and billing: operations

How to run project environments (GD-20, GD-21) and BRD billing (GD-22) in an installation.

## GitHub App

1. Create a GitHub App owned by Garuda with the repository permissions *Contents: Read* and *Metadata: Read*. It needs no webhooks. To let Estimators push approved delivery plans to GitHub Issues (GD-34 phase 2), also give it *Issues: Read and write* (see "Delivery plan push" below).
2. Mount its private key in the **worker** only: `SDLC_GITHUB_PRIVATE_KEY_FILE`, with `SDLC_GITHUB_APP_ID` and `SDLC_GITHUB_ENABLED=true` (see `secrets-and-key-management.md`). The API never holds GitHub credentials.
3. Each client installs the App on its GitHub organisation, on the repositories of its products.

When the App is not installed on a repository, its Environment Manager sees **App not installed** on the Environments tab. Ask the client's GitHub administrator to install it, then use **Check again**.

## Delivery plan push to GitHub Issues (GD-34 phase 2)

Garuda never writes code. The only write permission it ever asks for is *Issues: Read and write*, and only for the job that pushes a plan.

1. **Garuda (App owner):** GitHub → Settings → Developer settings → GitHub Apps → the Garuda App → *Permissions & events* → Repository permissions → *Issues: Read and write*. Save. GitHub then asks every installation to accept the new permission.
2. **Client (owner of the GitHub organisation or account where the App is installed):** GitHub → Settings → Applications → Installed GitHub Apps → the Garuda App → *Review request* → *Accept new permissions*. Until this is accepted, a push fails with "the Garuda GitHub App has not been granted Issues: Read and write", and nothing is written.
3. **Repository:** the plan is pushed to a **verified repository of the project** (Environments tab). For a new product this can be a new, empty repository: create it on GitHub, make sure the App installation includes it, and connect it on the Environments tab. Issues must be turned on in the repository's settings.

What a push does: one issue per story (acceptance criteria as a checklist, epic, sprint, role, priority and expected effort in the description), one milestone per sprint named `REQ-xxxxxx Sprint n` with the sprint's end date, and labels `garuda`, `epic: …`, `role: …`, `priority: …`. Pushing again updates the same issues and milestones (title, labels, milestone and description); acceptance criteria ticked on GitHub stay ticked, and closed issues stay closed. An issue deleted on GitHub is created again.

The token for a push is narrowed to the one repository with *Issues: Read and write* and *Metadata: Read*, never *Contents*. Writes are spaced by `SDLC_GITHUB_ISSUE_WRITE_PAUSE` (default 1 s) to respect GitHub's limits on content creation; a rate limit waits and continues where it stopped.

## Developer agent (GD-36)

Garuda's Developer agent writes code: it needs the App's **Contents: Read and write** and **Pull requests: Read and write** permissions (accepted by the installation owner) and the sandbox containers `devbox` and `devbox-proxy` (started by Docker Compose; set `SDLC_DEVBOX_TOKEN` in `.env`). It only ever pushes `garuda/...` branches and opens pull requests; a person merges. See `docs/architecture/garuda-developer-agent.md`.

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
