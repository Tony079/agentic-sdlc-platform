# System Overview (as built, 2026-11-07)

Authoritative design: TDD v1.0.4. This page describes what is implemented on `feature/r1-m5-m6-completion`; open
decisions and gaps are listed in `docs/testing/verification-matrix.md` and the decision register.

## 1. Runtime processes (one image, Spring profiles)

| Process | Profile | DB login | Responsibilities | Holds secrets |
|---|---|---|---|---|
| API | default | `sdlc_app` | REST API for clients and staff, sessions (Spring Session JDBC), CSRF, staff MFA, registration, review decisions | DB password, `SDLC_STORAGE_KEY`, `SDLC_MFA_KEY` |
| Worker | `worker` | `sdlc_worker` | Outbox dispatcher, job queue: document scan and extraction, repository verify/validate/snapshot/index, BA analysis and commit, release | DB password, `SDLC_STORAGE_KEY`, GitHub App private key (file), model provider config |
| Migrate | `migrate` | `sdlc_migrator` (acting as `sdlc_owner`) | Liquibase changesets 0001–0021, optional development seed; exits | migrator password |
| Provision | `provision` | `sdlc_provisioner` | Staff identities, two-person grants and assignments; exits | provisioner password, `SDLC_MFA_KEY` |
| Web | nginx | none | SPA (React/TypeScript/Vite), reverse proxy to the API | none |
| ClamAV | — | none | Malware scanning (development/CI scanner; production scanner O-03) | none |
| PostgreSQL 16 | — | — | Single database, schema `app`, forced RLS on every tenant table | — |

There is no RabbitMQ or Redis: messaging is the PostgreSQL transactional outbox plus a leased job queue (TDD 10–11,
decision Q-M3-4).

## 2. Modules (modular monolith, ArchUnit-enforced `api`/`internal` boundaries)

`common`, `tenancy`, `identity` (tenant users, staff, TOTP, provisioning), `audit`, `securitymonitoring`, `project`,
`requirement`, `workflow` (engine, transition table T01–T29 subset, stored definition R1-v1), `outbox`, `queue`,
`document`, `storage` (encrypted file store), `scanner`, `docprocessing` (isolated extractor subprocess), `modelgateway`
(provider-neutral; fake provider only), `sourcerepo` (C-18 links, registration, GitHub broker, snapshot, index),
`businessanalysis` (guard 9.4, input sets, BA agent, evidence checker, BRD, review, release, notifications), `migration`.

## 3. Trust boundaries

- **Tenant:** the tenant comes only from the session principal (client user, or a staff member's assignment-gated active tenant); `set_config` is transaction-local; forced RLS everywhere.
- **API vs worker:** separate logins with column-level grants; the API cannot touch jobs or delivery state; only the worker holds GitHub credentials.
- **Privileged administration:** only `sdlc_provisioner` can create staff or write grants; triggers enforce the two-person rules.
- **Untrusted content:** repository files, documents, extracted text and model output are data. Model output is schema-validated, stored as a pending result, and only the trusted commit step (Tx C) creates BRD content, after citation verification.
- **Human gates:** BRD approval by a Business Reviewer (four-eyes), release after re-checks, client view only after release.

## 4. Requirement lifecycle (implemented transitions)

```
DRAFT -T01-> SUBMITTED -T02-> REPO_VALIDATING -T06-> AWAITING_DOCUMENTS -T11-> ANALYSING -T18-> BRD_DRAFTED
   -T20-> INTERNAL_REVIEW -T21-> INTERNAL_APPROVED -T25-> SHARED_WITH_CLIENT -T27-> CLIENT_CHANGES_REQUESTED -T28-> ANALYSING
SUBMITTED -T03-> MANUAL_INTAKE          REPO_VALIDATING -T07-> AWAITING_DOCUMENT_DECISION
REPO_VALIDATING -T09-> FAILED           ANALYSING -T19-> FAILED      ANALYSING -T14-> AWAITING_DOCUMENTS / -T15-> ANALYSING
INTERNAL_REVIEW -T22-> REVISION_REQUESTED -T24-> ANALYSING            INTERNAL_REVIEW -T23-> REJECTED
any non-terminal -T29-> CANCELLED (owner)
```
Not implemented: T04, T05, T08, T10, T12, T13, T16, T17, T26 (and T30, which is not in the PRD).
