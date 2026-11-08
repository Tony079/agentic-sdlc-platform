# External Integration Verification (2026-11-08)

**Summary: no external integration has been verified against a real service.** Every integration below was tested against an in-process fake or a local container. Fake-service results show the platform's own behaviour (protocol handling, refusal rules, limits). They say nothing about the real service's behaviour, availability or terms.

| Integration | Real or mocked in tests | What was verified | Real verification status | What is needed |
|---|---|---|---|---|
| **GitHub App** (repository verify, validate, snapshot) | **Mocked**: `support/FakeGitHub`, an HTTP server on loopback serving App JWT exchange, installation tokens, repository metadata, branch head and tarballs | App JWT (RS256, ≤ 9 min); installation token narrowed to one repository with `contents:read`/`metadata:read`, and a wider token refused; HTTPS and host allowlist (loopback HTTP allowed only by a test flag); one redirect at most, without forwarding the token; 1 MB JSON cap; rate-limit deferral; tarball limits; SHA pinning; ownership clash (`RepositoryIntegrationIT`, `GitHubBrokerTest`) | **NOT VERIFIED** | A GitHub App (App id, private key PEM), installed on a test organisation with a test repository; the hosts `api.github.com` and `codeload.github.com` allowed; then a run of the REPO_VERIFY → REPO_SNAPSHOT → REPO_INDEX path against it |
| **LLM provider** | **Mocked**: `FakeModelProvider` (deterministic, no network) | Gateway contract: prompt registry, strict schema binding with one repair attempt, transient retry and deferral, rate-limit deferral, timeout enforcement, budget caps, usage recording (marked ESTIMATED), tool-calling loop with registry refusal and limits (`ModelGatewayIT`, 22 tests); BA Phase A/B and evidence verification (`AnalysisIT`, `ReviewIT`, `ClarificationIT`) | **NOT VERIFIED.** No provider adapter exists. O-01 (provider selection) is open; FR-LLM-10 (evaluating OpenAI, Anthropic and Google) has not been done | 1. An owner decision on O-01 (data-processing terms, retention, region). 2. An adapter implementing `ModelProvider` with native structured output and tool mapping. 3. An API key supplied through the secret store. 4. The FR-LLM-10 evaluation |
| **Malware scanner** | Local **ClamAV container** in CI (PR #4); `FakeClamd` in unit tests | clamd INSTREAM protocol, fail-closed on outage, quarantine, MALWARE_DETECTED event | **Development/CI only.** The production scanner (O-03) is undecided | O-03 decision and its integration test |
| **Object store** | Local encrypted file store (`EncryptedFileObjectStore`, AES-256-GCM) | Opaque keys, encryption, integrity on read, size caps, deletion (`EncryptedFileObjectStoreTest`, `RetentionIT`) | **Development only.** Production store and KMS (O-12/C-13) are undecided; the key is static, with no rotation | O-12/C-13 decision; an S3-compatible or cloud adapter with KMS |
| **Email / external notifications** | None (in-app only, AC-EXT-40) | No email code exists | n/a | n/a |
| **Docker / Compose runtime** | `docker compose config` only | The configuration is valid, including the `tools` profile (migrate, provision, retention) | **NOT RUN**: no Docker daemon in this environment | A host with Docker: `docker compose up`, then the smoke checks in `local-development.md` |
| **GitHub Actions CI** | — | Last green on PR #6 (`e3feb84`) | **NOT RUN** on the R1-M4, R1-M5/M6 or MVP-completion branches: pushing has not been authorised | Authorisation to push the branch (without modifying PRs #1–#6) |

## Credentials and access checklist (owner to supply; never commit)

| Item | Where it goes | Who decides |
|---|---|---|
| GitHub App id | `SDLC_GITHUB_APP_ID` (worker environment) | Platform owner |
| GitHub App private key (PEM) | File mounted read-only into the worker; `SDLC_GITHUB_PRIVATE_KEY_FILE` | Platform owner; store it in a secret manager |
| Test GitHub organisation and repository, with the App installed (read-only) | — | Platform owner |
| LLM provider choice | Owner decision O-01 | Product Owner, Security Owner |
| LLM API key | Secret store, injected into the worker only | After O-01 |
| Network egress for `api.github.com`, `codeload.github.com` and the chosen LLM host | Environment network policy | Operations |

No credential was available to this session, and none was invented. No test uses a real key.
