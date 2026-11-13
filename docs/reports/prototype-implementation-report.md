# Working Prototype Implementation Report (2026-11-13)

| | |
|---|---|
| Branch | `feature/mvp-production-readiness`, local only. **Nothing pushed, merged or deployed** |
| Baseline | `cc05322` (clean working tree) |
| GCP | Not touched: no resource created, changed or inspected |
| How to run it | `docs/operations/prototype-guide.md` |
| What is postponed | `docs/operations/prototype-deferred-work-register.md` |

## 1. Existing implementation assessment

The repository already held a broad modular monolith (Java 21, Spring Boot 4.1, PostgreSQL 16, Liquibase, React and Vite) with 296 passing backend tests and 40 frontend tests. All ten workflow steps of the prototype brief existed as code.

**It could not be demonstrated,** for three reasons:

| Gap | Effect |
|---|---|
| Repository access existed only through the GitHub App broker, which needs real credentials | A requirement could never leave repository validation, so analysis never started |
| The only offline model provider (`fake`) returns empty output unless a test scripts it | No BRD could be produced outside tests |
| No dashboard, no audit-history view, and a laborious manual staff setup | The UI could not carry a user through the workflow |

## 2. Features completed in this phase

| Feature | Detail |
|---|---|
| Repository adapter boundary | `RepositoryAccess` port used by the verify, validate and snapshot jobs. Two adapters: GitHub App (default) and **development** (bundled sample order service, owner `sdlc-dev` only) |
| Development mock model provider | `development-mock`: builds schema-valid Phase A and Phase B output from the real inputs. Quotes are exact, so the platform's evidence verifier checks them like any citation. Asks one blocking clarification until it is answered. Every statement starts with `[DEVELOPMENT MOCK]` |
| Mock labelling | Provider name stored on every model call and BRD version; UI notice on each mock version (client and staff); site-wide "Prototype mode" banner from `GET /api/v1/runtime` |
| Fail-closed guard | Production mode refuses to start with either development adapter |
| Audit history | `GET /api/v1/requirements/{id}/audit`: owner only, content-free, covers the requirement and its documents, clarifications and BRD versions |
| Dashboard | Counts by workflow group, open clarifications, recent requirements; loading, empty and error states |
| Workflow status | Step indicator on the requirement page; automatic refresh while the worker is moving the requirement; state explanations |
| Demo setup | `scripts/prototype_demo_setup.py` creates four staff accounts through the real provisioning and MFA paths; `scripts/totp.py` prints codes |
| Compose overlay | `docker-compose.prototype.yml` switches the two adapters on |

No workflow gate, authorisation rule, RLS policy or approval binding was changed or bypassed.

## 3. Features remaining

- Real model analysis (provider choice and benchmark), real GitHub access, and everything else in the deferred-work register (16 items).
- A Compose start has not been verified (no Docker daemon in the build environment).
- No staff UI for provisioning.
- Reviewer "editing" is revision notes plus a new version, as the workflow defines; there is no direct text editing of a BRD.

## 4. Architecture changes

| Change | Why |
|---|---|
| New port `sourcerepo/internal/RepositoryAccess`; the three repository job handlers depend on it, not on `GitHubBroker` | Lets a development adapter, and later other sources, replace GitHub without touching the jobs |
| New provider `modelgateway/internal/DevelopmentMockModelProvider` behind the existing `ModelProvider` SPI | No change to the gateway or the BA agent |
| New read API `audit/api/AuditTrail` | The audit module had a writer only |
| `operations/internal/RuntimeInfoController` | Lets the UI state which adapters are active |

The modular-monolith boundaries (ArchUnit) still hold. No new dependency, service or infrastructure component was added. Existing boundaries for later GCP work: `ObjectStore` (Cloud Storage), `KeyManagement` (Cloud KMS), `SecretsProvider` (Secret Manager).

## 5. Database migrations added

| Changeset | Content |
|---|---|
| `0030-development-mock-model.sql` | Registers `development-mock` / `development-mock-1` in `model_registry` (data class FAKE) and widens the provider/data-class check to include it. No applied changeset was modified |

Total: 105 changesets, applied to an empty database in the verification run.

## 6. APIs

87 endpoints exist across 16 controllers. Added in this phase:

| Endpoint | Purpose | Access |
|---|---|---|
| `GET /api/v1/requirements/{id}/audit` | Audit history | Requirement owner; others get 404 |
| `GET /api/v1/runtime` | Names of the configured model provider and repository source | Any signed-in client user or staff member |

The prototype workflow uses the existing endpoints for projects, requirements, documents, repository links and registration, clarifications, staff review, approval and release.

## 7. Frontend screens

| Screen | Status |
|---|---|
| Dashboard (`/`) | **New** |
| Projects: list and create | Existing |
| Requirement form, list, detail | Existing; detail now has the step indicator, live refresh, state explanations and audit history |
| Document upload | Existing |
| Clarifications (client) and reviewer inbox | Existing |
| BRD review, approval, revision, rejection (staff) | Existing; mock notice added |
| Release approval and other staff operations | Existing; reason hint added |
| Audit history | **New** (section of the requirement page) |

## 8. Test results (executed 2026-11-13)

| Suite | Result |
|---|---|
| Backend `mvn verify`, PostgreSQL 16 | **307 tests, 0 failures, 0 errors, 0 skipped** (100 unit, 207 integration). Before: 296 |
| Frontend Vitest | **48 of 48** in 11 files. Before: 40 |

New tests:

| Test | Covers |
|---|---|
| `PrototypeWorkflowIT` (3) | The whole workflow on the real API and worker with the development adapters: creation, document upload and extraction, analysis, a persisted clarification and its resolution, BRD generation with verified evidence, approval bound to the content hash, separate release, workflow history, audit events, tenant and owner isolation of the audit endpoint; a repository outside the sample owner is refused; the runtime endpoint |
| `DevelopmentMockModelProviderTest` (4) | Deterministic output, exact quotes, marker on every statement, no repeated clarification |
| `DevelopmentRepositoryAccessTest` (2) | Tarball accepted by the real snapshot reader; everything else refused |
| `ProductionConfigurationValidatorTest` (+1), `RuntimeInfoControllerTest` (1) | Production refusal; adapter naming |
| `Prototype.test.tsx` (8) | Dashboard states, audit history states, mock notice and banner, step indicator |

Areas the brief lists that were already covered and still pass: requirement validation (`RequirementApiIT`), tenant isolation (`RlsIsolationIT`, `SchemaSecurityIT`), upload validation (`DocumentApiIT`), workflow transitions (`WorkflowConsistencyIT`), job idempotency and fencing (`JobQueueIT`, `OutboxDispatcherIT`), approval invalidation when inputs change (`ReviewIT`, `ClarificationIT`).

## 9. Build results

| Check | Result |
|---|---|
| Backend compile and package | OK |
| Frontend `tsc --noEmit`, ESLint, `vite build` | OK |
| `docker compose -f docker-compose.yml -f docker-compose.prototype.yml config` | Valid |
| gitleaks, full history and working tree | No leaks |

## 10. End-to-end verification against the definition of a working prototype

Run on 2026-11-12 and 2026-11-13 with the packaged jar as API and worker processes, PostgreSQL 16, and the UI in Chromium.

| # | Criterion | Result |
|---|---|---|
| 1 | Frontend starts | Yes (Vite development server). **The nginx image was not started** |
| 2 | Backend starts | Yes: API readiness UP; worker liveness file fresh |
| 3 | PostgreSQL starts | Yes (local cluster). **Not through Compose** |
| 4 | Liquibase migrations run | Yes: 105 changesets on an empty database, exit 0 |
| 5 | Create and submit a requirement | Yes, in the browser |
| 6 | Upload and process a document | Yes: scanned, extracted in the sandbox, state Processed. **The scanner was a protocol stand-in, not ClamAV** |
| 7 | BA analysis persisted | Yes: 4 model calls, 1 BRD version, 11 items. **Mock output** |
| 8 | Clarification created and resolved | Yes |
| 9 | BRD reviewed and approved | Yes; then released by a different staff member after five passing gates |
| 10 | State and audit persist after restart | Yes: identical before and after an application restart and after a full stop of every process including PostgreSQL; the workflow then continued |
| 11 | Automated tests pass | Yes (§8) |
| 12 | Setup documented | `docs/operations/prototype-guide.md` |

**Not verified: `docker compose up`.** The build environment has no Docker daemon, so criteria 1 to 3 are met with the same artefacts and configuration but not through Compose. The first Compose start on a Docker host is the remaining check.

## 11. Known limitations

See `prototype-guide.md` §8. In short: mock BRD content; one sample repository; the Compose start is unverified; staff setup needs the script.

## 12. Commits (local)

| Commit | Content |
|---|---|
| `8c07d6e` | Development adapters, audit history, runtime info, changeset 0030, backend tests |
| `7121e48` | Dashboard, audit history view, step indicator, mock labels, frontend tests |
| (this commit) | Compose overlay, demo scripts, sign-in lands on the dashboard, history refresh fix, guide, deferred-work register, this report |
