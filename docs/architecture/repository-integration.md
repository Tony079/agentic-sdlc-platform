# Repository Integration (PRD R1-M4)

Implements C-01/O-05, C-18 (Option 1), Q-A (Option B) and Q-B (Option A) of decision register section 9, and
TDD v1.0.4 section 14 within those decisions. Static analysis only: client repository content is never compiled,
built or executed.

## 1. Statuses (kept distinct)

| Stage | Where | States |
|---|---|---|
| Link request issued | `repository_link_request` | PENDING → USED / REVOKED (expiry by time) |
| Link submitted and authorised (Q-A) | `requirement_repository_link` | AUTHORIZED → SUPERSEDED |
| Repository registered (C-01) | `repository` | PENDING_VERIFICATION → VERIFIED / UNAVAILABLE / ACCESS_DENIED |
| Repository validated, SHA pinned | `requirement_repository` | PENDING_VALIDATION → VALIDATED (pinned SHA) / FAILED |
| Snapshot created | `repository_snapshot` | STORED → INDEXED, or FAILED |
| Inventory completed | `repository_file`, `code_symbol`, `build_dependency` | rows exist for an INDEXED snapshot |

## 2. Flow

1. **Link request (C-18 design, Option 1).** The requirement owner issues a one-time request. The token is 256 random bits; only its SHA-256 is stored. It is bound to tenant, requirement, operation `ATTACH_REPOSITORY_LINK` and an expiry of 1–168 hours (default 72).
2. **Redemption (Q-A authorisation).** A `CLIENT_APPROVER` of the same tenant redeems the token with a repository URL. In one transaction:
   - the request is locked;
   - the request, the requirement state and the URL syntax are checked;
   - `requirement_repository_link` (AUTHORIZED) is inserted;
   - the request is marked USED;
   - audit `REPOSITORY_LINK_ATTACHED` is written.

   The response is `{linkId, status}` only. Every failure is the same 404 plus a security event.
3. **Registration (C-01).** Staff call `POST /api/v1/projects/{id}/repositories {linkId}` with MFA, the `REPOSITORY_REGISTER` capability and an active assignment to the tenant.
   - The repository owner and name come from the client-authorised link, never from staff input.
   - It inserts `repository` (PENDING_VERIFICATION), `project_repository` and `requirement_repository` (PENDING_VALIDATION).
   - It writes audit `REPOSITORY_REGISTERED` (actor STAFF) and publishes outbox `RepositoryRegistered`.
   - The API holds no GitHub credentials.
4. **REPO_VERIFY (worker).** The broker checks the App installation for the repository and obtains a token scoped to that one repository, Contents: Read and Metadata: Read.
   - It reads the repository id and default branch from GitHub's response (trusted data, not the URL text).
   - It stores `github_repo_id`. This value is unique across tenants; a clash becomes a generic `UNAVAILABLE` and reveals nothing.
   - On success: VERIFIED, audit `REPOSITORY_VALIDATED`, and the requirement's REPO_VALIDATE job is made available.
5. **REPO_VALIDATE (worker, enqueued by T02).**
   - If the requirement's repository is not VERIFIED yet: deferral (no retry budget used).
   - Otherwise it resolves the default branch head and **pins the commit SHA** on `requirement_repository`.
   - It then performs **T06** (REPO_VALIDATING → AWAITING_DOCUMENTS), or **T07** (→ AWAITING_DOCUMENT_DECISION) when an attached document needs a decision.
   - It enqueues REPO_SNAPSHOT. **No T08, no ANALYSING, no BA job** (Q-B).
6. **REPO_SNAPSHOT.**
   - Downloads the tarball for the pinned SHA from `api.github.com`, following at most one redirect and only to `codeload.github.com`, HTTPS only.
   - Checks the archive's pax `comment` against the pinned SHA.
   - Stores the archive encrypted through `ObjectStore`.
   - Records the file inventory: path, language, size, SHA-256, supported/skip reason.
   - Writes audit `SNAPSHOT_CREATED` and enqueues REPO_INDEX.
7. **REPO_INDEX.**
   - Java symbols via JavaParser on files of at most 1 MB, with a per-file time limit: package, type, method, Spring stereotype, path, line range.
   - `pom.xml` (hardened StAX) and `build.gradle` (text patterns): dependency coordinates only.
   - The snapshot becomes INDEXED.

**Failures:**
- GitHub 404/403 in REPO_VERIFY: ACCESS_DENIED plus audit `REPOSITORY_ACCESS_DENIED`. The requirement's REPO_VALIDATE then fails fatally (`REPO_ACCESS`).
- A dead REPO_VALIDATE job leads to **T09** (REPO_VALIDATING → FAILED; existing TDD contract).
- 429: deferral with the provider's retry delay. 5xx: retryable under the existing job-type budget.
- **No administrative retry and no T30** (O-27 / S-06 open).

## 3. GitHub broker

- **Configuration:**
  - `sdlc.github.enabled` (default **false**);
  - `sdlc.github.app-id`;
  - `sdlc.github.private-key-file` (a PEM file mounted from a secret store; never an inline value);
  - `sdlc.github.api-base-url` (default `https://api.github.com`);
  - `sdlc.github.allowed-hosts` (default `api.github.com, codeload.github.com`).
- **App JWT:** RS256, at most 9 minutes. Installation tokens are requested per job with `repository_ids=[id]` and permissions `{contents: read, metadata: read}`. They are kept in memory for one job, never persisted, logged or passed to agents.
- **HTTP:**
  - every request URL must be HTTPS on an allowed host (plain HTTP only for loopback test servers);
  - redirects are not followed automatically; one redirect is allowed, only to an allowed host;
  - response bodies are size-capped.
- **Where it runs:** only in the worker. The private key is never in the API process, Git, `.env` files, Compose, the frontend or CI logs.

## 4. Limits (TDD 14.5)

- Archive: at most 200 MB (compressed), at most 50,000 entries, and a total expanded size of at most 1 GB with an expansion ratio of at most 100.
- Indexed file: at most 1 MB.
- Entries with absolute paths, `..` segments or NUL characters are rejected from the inventory.
- Symlinks, hard links, devices and FIFOs are skipped and listed with a reason.
- Nothing is written to the file system from the archive: entries are only streamed and hashed.

## 5. API (as built)

| Endpoint | Caller | Result |
|---|---|---|
| `POST /api/v1/requirements/{id}/repository-link-requests {expiresInHours?}` | Requirement owner | 201 `{requestId, token, expiresAt}` (token once, `Cache-Control: no-store`); 404 if not the owner; 409 `LINK_NOT_ALLOWED_IN_STATE`; 422 for an expiry outside 1–168 hours |
| `DELETE /api/v1/repository-link-requests/{requestId}` | Issuing owner | 204; 404 otherwise |
| `GET /api/v1/requirements/{id}/repository-link` | Requirement owner | Pending request (id, expiry), authorised link (URL, status), validation (status, pinned SHA, branch, failure code, snapshot status, file count) |
| `POST /api/v1/repository-link-requests/redeem {token, repositoryUrl}` | Client Approver | 201 `{linkId, status}`; 422 for URL syntax; **404 for every token failure** |
| `GET /api/v1/staff/repository-links` | Staff with `REPOSITORY_REGISTER`, active tenant | AUTHORIZED links of the active tenant: link id, URL, project id, time. No requirement data |
| `POST /api/v1/projects/{id}/repositories {linkId}` | Staff with MFA, assignment, `REPOSITORY_REGISTER` | 202 `{repositoryId, status}`; 403 without the capability or assignment; 404 for an unknown, used or other-tenant link or a wrong project; 409 `REPOSITORY_ALREADY_REGISTERED` / `REGISTRATION_NOT_ALLOWED_IN_STATE` |
| `GET /api/v1/projects/{id}/repositories` | Tenant users; staff with an active assignment | Repository status, default branch, times. No credentials, no content |

Configuration (worker only): `SDLC_GITHUB_ENABLED` (default `false`), `SDLC_GITHUB_APP_ID`,
`SDLC_GITHUB_PRIVATE_KEY_FILE` (path to a mounted PEM file, PKCS#8 or GitHub's PKCS#1). With the integration off,
REPO_VERIFY defers with `GITHUB_NOT_CONFIGURED` and nothing is contacted.

## 6. Test evidence (fake GitHub only)

`FakeGitHub` (test code) generates its RSA key at runtime, verifies App JWT signatures, issues and checks scoped
tokens, records token requests and serves tarballs through a redirect to a second loopback host.
- `GitHubBrokerTest`: token narrowing, refusal of wider grants, redirect host refusal, token not forwarded, HTTPS rule, rate-limit and 5xx classification, PKCS#1/PKCS#8 keys.
- `SnapshotArchiveTest`: inventory, pax SHA check, unsafe paths, links and FIFOs, entry, size and ratio limits, corrupt input.
- `IndexerTest`: Java symbols and mappings, XXE-safe pom parsing, Gradle coordinates.
- `LinkRequestIT`: C-18.
- `RepositoryIntegrationIT`: end to end, permission matrix, T07, T09, cross-tenant clash, deferral, database privileges.

**Real GitHub has not been tested.** That needs a GitHub App, a test organisation with a test repository and a secret
store for the key (owner/operator action).

## 7. Open gates

- **O-25:** snapshots are kept with no retention or deletion job; production retention is blocked.
- **O-12 / C-13:** the development/test encrypted store only.
- **O-27 / S-06:** no administrative retry.
- **GitHub App:** not configured; integration disabled by default; verified only against a fake GitHub server.
