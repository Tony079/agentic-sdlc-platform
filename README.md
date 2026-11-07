# Agentic SDLC Platform

Turns a client requirement into reviewed, versioned engineering documents under human approval.
Product scope: [`docs/product/PRD-v0.7.md`](docs/product/PRD-v0.7.md) (authority).
Architecture: [`docs/architecture/TDD-v1.0.4.md`](docs/architecture/TDD-v1.0.4.md).
Open decisions, conflicts and security gates: [`docs/decisions/decision-register.md`](docs/decisions/decision-register.md).

## Status

**Milestone 1 — foundation.** Tenant-aware sign-in, projects, audit trail and security events, on a modular
monolith with PostgreSQL Row-Level Security.

**Milestone 2 — requirement management.** Create, edit, view, submit and cancel requirements with the eight PRD
FR-REQ-01 fields, human-readable keys (`REQ-000123`), immutable revisions, optimistic locking, idempotent creation and
a database-enforced workflow history (DRAFT → SUBMITTED, cancel). Requirements are visible to their owner only.

**Milestone 3 — transactional outbox, job queue and routing.** Submission writes `RequirementSubmitted` to a
transactional outbox in the same transaction. A separate **worker** process (login `sdlc_worker`) delivers events
tenant by tenant under RLS and routes the requirement: existing repository → `REPO_VALIDATING` (a repository-validation
job is queued; its handler arrives with the repository milestone), new product → `MANUAL_INTAKE`. The PostgreSQL job
queue has leases, heartbeats, fencing epochs, bounded retries with backoff, dead-lettering and crash recovery. After
submission the requirement type is locked; routed requirements are no longer editable.

**Milestone 4 — attachments.** Clients attach PDF, DOCX, TXT and Markdown files (10 files, 20 MB each, 100 MB per
requirement). Files are streamed into a private, encrypted store under opaque keys; the worker validates the real
content (signatures, DOCX structure and decompression limits, text encoding) and scans it with ClamAV. Only clean files
can be downloaded; malware is blocked and raises a security event; a scanner outage keeps files quarantined.

**Milestone 5 — document processing.** Clean files are turned into text chunks (with page or heading labels and a
full-text index) by an extractor that runs as a separate, restricted process; the worker validates its output before
storing anything. Encrypted and image-only files are marked as not analysed (no OCR). **Extraction is switched off by
default** (`SDLC_DOCUMENT_EXTRACTION_ENABLED`) until the extractor isolation decision O-15 is closed; the local Compose
stack enables it for synthetic test documents only.

Review, clarification and approval workflows follow. Staff roles (Business Reviewer, Auditor, Platform Admin) wait
for owner decision O-06.

## Layout

| Path | Contents |
|---|---|
| `backend/` | Java 21, Spring Boot 4.1, Maven. Modules under `io.github.tony079.sdlc`: `common`, `tenancy`, `identity`, `audit`, `securitymonitoring`, `project`, `requirement`, `workflow`, `outbox`, `queue`, `document`, `storage`, `scanner`, `docprocessing`, `modelgateway`, `migration` |
| `backend/src/main/resources/db/changelog/` | Liquibase schema (applied only by the `migrate` process) |
| `db/bootstrap/` | Database roles and schemas, run once by the PostgreSQL superuser |
| `frontend/` | React 19, TypeScript, Vite |
| `docker-compose.yml` | Local stack: PostgreSQL, migrate (one-shot), api, worker, ClamAV, web (nginx) |

## Security model (Milestone 1)

- Four database roles: `sdlc_owner` (owns objects, cannot log in), `sdlc_migrator` (Liquibase, acts as owner),
  `sdlc_app` (API runtime: no owner rights, no RLS bypass, no DDL) and `sdlc_worker` (background worker: the same
  restrictions, column-level grants for outbox delivery and the job queue, no DELETE, no access to requirement
  content). The API never holds migration or worker credentials and cannot mark events delivered or touch jobs.
- Every tenant-owned table has `tenant_id NOT NULL`, composite keys and **forced** RLS. The tenant comes only from
  the authenticated session and is set per transaction (`set_config(..., true)`), so pooled connections cannot leak it.
- Requests for another tenant's resource return 404 and are recorded as security events.
- Audit trail is append-only for the application role and written in the same transaction as the change.
- Server-side sessions (HttpOnly, SameSite=Strict cookies), CSRF token header, Argon2id passwords, login throttling.

## Run locally with Docker Compose

```bash
cp .env.example .env
```

Edit `.env` and replace every placeholder value, then:

```bash
docker compose up --build
```

Open <http://localhost:8080> and sign in with organisation `acme`, email `alice@acme.test` and the
`SDLC_DEV_SEED_PASSWORD` from `.env`. Tenant `globex` (`gina@globex.test`) is a second tenant for isolation checks.

## Develop and test

Backend tests need PostgreSQL 16. They use Testcontainers when Docker is available, or an existing server
given by a superuser URL (the tests create their own roles, database and data):

```bash
cd backend && ./mvnw verify
```

```bash
cd backend && SDLC_TEST_ADMIN_JDBC_URL=jdbc:postgresql://localhost:5432/postgres SDLC_TEST_ADMIN_USER=postgres SDLC_TEST_ADMIN_PASSWORD=... ./mvnw verify
```

Frontend:

```bash
cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build
```

Run the frontend against a local API (proxied, single origin):

```bash
cd frontend && SDLC_API_URL=http://localhost:8080 npm run dev
```
