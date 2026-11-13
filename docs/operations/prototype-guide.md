# Working Prototype Guide

This guide starts the Agentic SDLC Platform prototype on one machine and walks through the whole Release 1 workflow: requirement, documents, analysis, clarification, BRD, review, approval, release and audit history.

> **This is a local prototype, not a deployment.**
> - It uses two **development adapters**, described in §2. BRD content is **mock output**, labelled as such everywhere. No language model is called.
> - Nothing runs in GCP. PostgreSQL and document storage are local Docker volumes. The database port is not published.
> - What is postponed is listed in `prototype-deferred-work-register.md`.

## 1. Prerequisites

- Docker with Compose v2.
- Python 3.9 or later (standard library only), for the staff setup script.
- Internet access on first start: Docker images, and ClamAV downloads its signatures.

## 2. What is real and what is a development adapter

| Part | In the prototype |
|---|---|
| API, worker, workflow engine, job queue, transactional outbox | Real |
| PostgreSQL 16 with forced row-level security, five separate logins, Liquibase | Real (local container) |
| Document upload, validation, malware scan (ClamAV), encrypted storage, text extraction | Real (local volume; development scanner image) |
| Evidence verifier, clarifications, approvals bound to content and inputs, manual release, release gates, audit | Real |
| Staff identity: TOTP MFA, two-person capability grants | Real. The first two administrators use `bootstrap-admins`, which works only in development |
| **Model provider** | **Development mock** (`development-mock`). It assembles a BRD from the inputs by fixed rules: it quotes one real line from each selected repository file and the start of each document chunk, and asks one fixed blocking question on the first run. Every statement starts with `[DEVELOPMENT MOCK]` |
| **Repository source** | **Development adapter.** Any repository of owner `sdlc-dev` (for example `https://github.com/sdlc-dev/sample-orders`) is served from a bundled sample order service. Any other owner is refused, as an uninstalled GitHub App would be. GitHub is never contacted |

The UI shows a yellow "Prototype mode" banner while these adapters are active, and a "Development mock output" notice on every BRD version the mock produced. Production mode refuses to start with either adapter.

## 3. Start

1. Create the environment file and replace **every** placeholder:

   ```bash
   cp .env.example .env
   ```

   Use `openssl rand -hex 24` for each password, and `openssl rand -base64 32` for `SDLC_STORAGE_KEY` and `SDLC_MFA_KEY`. Keep `SDLC_DEV_SEED=true` and choose a `SDLC_DEV_SEED_PASSWORD`; it is the password of the demo client users.

2. Start the stack with the prototype overlay:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prototype.yml up --build
   ```

   Order: `db`, then `migrate` (Liquibase, 105 changesets, exits), then `api`, `worker`, `clamav`, then `web`.

3. Open `http://localhost:8080` (or the port in `SDLC_WEB_PORT`).

4. Create the demo staff accounts. Run this once, in a second terminal, from the repository root; it takes about three minutes:

   ```bash
   python3 scripts/prototype_demo_setup.py
   ```

   - The script uses only the real paths: the `provision` process and the staff sign-in API. It waits between steps because each administrator's TOTP code can be used once.
   - It writes passwords and TOTP secrets to `.prototype-demo/credentials.json` (git-ignored).
   - If you changed the web port, set `SDLC_BASE_URL`, for example `SDLC_BASE_URL=http://localhost:9090`.

**ClamAV note.** Until ClamAV has downloaded its signatures (a few minutes on first start), uploads stay in quarantine. That is the fail-closed design; they are scanned as soon as it is ready.

### Accounts

| Who | Sign-in | Role |
|---|---|---|
| `alice@acme.test` | `/login`, organisation `acme`, password `SDLC_DEV_SEED_PASSWORD` | Client user; owns the requirement |
| `aaron@acme.test` | same | Client Approver; authorises the repository |
| `reviewer@prototype.test` | `/staff/login` | Staff: registers repositories, reviews and approves BRDs |
| `release@prototype.test` | `/staff/login` | Staff: release manager (must differ from the reviewer) |

For a staff sign-in, print the password and the current code:

```bash
python3 scripts/totp.py reviewer@prototype.test
```

A code works once. If a sign-in is refused, wait for the next code (30 seconds).

## 4. Demo walkthrough

| # | Who | Action | What to expect |
|---|---|---|---|
| 1 | Alice | Sign in. Open **Projects**, create a project | The dashboard shows the prototype banner |
| 2 | Alice | **New requirement**: choose the project, fill every field, Repository URL `https://github.com/sdlc-dev/sample-orders`. **Save draft** | The requirement page opens in state DRAFT |
| 3 | Alice | In **Repository**, click **Ask an approver to link a repository** and copy the one-time code | A code starting `rlr_` |
| 4 | Alice | In **Attachments**, add a TXT, Markdown, PDF or DOCX file | The file goes from scanning to **Processed** |
| 5 | Aaron | Sign in. **Link a repository**: paste the code and the same repository URL. **Authorise repository** | "Repository authorised" |
| 6 | Reviewer | Staff sign-in. Choose **Acme Corporation** as active organisation. Under **Repositories awaiting registration**, click **Register** | The entry disappears |
| 7 | Alice | On the requirement page, click **Submit** | The page updates by itself: Submitted, Repository and documents, Analysis. It stops at AWAITING_CLARIFICATION within seconds |
| 8 | Alice | Open **Clarifications**. Answer the question marked `[DEVELOPMENT MOCK]` | The analysis resumes; the requirement reaches INTERNAL_REVIEW |
| 9 | Reviewer | Staff workspace, **BRDs awaiting review**: open the version. Read the items and their verified evidence. Click **Approve this version** | State INTERNAL_APPROVED. Alice still cannot see the BRD |
| 10 | Release manager | Staff sign-in, choose Acme, open **Operations**. Under **Releases awaiting approval**, check the gates, type a reason, click **Approve release** | The worker re-checks the gates and releases |
| 11 | Alice | Open the requirement | State SHARED_WITH_CLIENT; the BRD is shown with the mock notice. **Workflow history** lists every transition; **Show audit history** lists every action without content |

**Optional: reviewer corrections.** Instead of approving in step 9, the reviewer can enter correction notes and click **Request revision**. The platform produces version 2 with the notes as input. The author of the notes cannot approve that version (four-eyes rule), so approving it needs a second staff member with `BUSINESS_REVIEWER`.

**Persistence check.** Stop and start the stack at any step:

```bash
docker compose -f docker-compose.yml -f docker-compose.prototype.yml restart api worker
```

State, history, clarifications, BRD versions and audit entries are all in PostgreSQL and the document volume, so the workflow continues where it was.

## 5. Stop and reset

Stop, keeping data:

```bash
docker compose -f docker-compose.yml -f docker-compose.prototype.yml down
```

Reset everything (database, documents, demo staff):

```bash
docker compose -f docker-compose.yml -f docker-compose.prototype.yml down -v
```

```bash
rm -rf .prototype-demo
```

## 6. Running the tests

Backend (needs Docker for Testcontainers, or a local PostgreSQL 16 superuser URL in `SDLC_TEST_ADMIN_JDBC_URL`, `SDLC_TEST_ADMIN_USER`, `SDLC_TEST_ADMIN_PASSWORD`):

```bash
cd backend && mvn verify
```

Frontend:

```bash
cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build
```

`PrototypeWorkflowIT` runs this guide's whole workflow against the real API and worker with the two development adapters.

## 7. Verification status of this guide

| Check | Result (2026-11-13) |
|---|---|
| Walkthrough steps 1–11 in a real browser (Chromium) against a running API, worker and PostgreSQL 16 | **Done**, all steps passed |
| Restart during the workflow, including a full stop of PostgreSQL | **Done**: state, 9 transitions, 29 audit rows and the BRD version were identical afterwards, and the workflow continued |
| `scripts/prototype_demo_setup.py` | **Done**, against that stack |
| `docker compose ... config` with the overlay | Valid |
| `docker compose ... up` | **Not run: the build environment has no Docker daemon.** The run above used the same packaged jar and the same configuration values, started as plain processes, with the Vite development server instead of the nginx image and a protocol stand-in instead of the ClamAV container. The Compose start itself is therefore unverified |

## 8. Known limitations

- BRD content is placeholder text from the mock. It demonstrates the workflow, not analysis quality.
- Only the bundled sample repository can be analysed.
- One reviewer cannot both correct and approve a version (by design).
- The staff UI has no page for provisioning; staff accounts come from the script or the `provision` commands.
- There is no direct text editing of a BRD: corrections go through revision notes and a new version, as the workflow defines.
- Nothing here is hardened for a shared environment. See `prototype-deferred-work-register.md`.
