# External Integration Verification (Deliverable E, 2026-11-08)

**Summary: no external integration has been verified against a real service.** Each row says what was tested and against what. A mock or loopback result shows the platform's own behaviour (request shape, refusal rules, limits, error mapping). It does not prove the real service's behaviour, availability or terms.

| Integration | Real or mocked in tests | What was verified (executed) | Real verification | What is needed |
|---|---|---|---|---|
| **Anthropic Messages API** (O-01) | **Mocked:** loopback HTTP server in `HostedProviderAdaptersTest` | Request to `/v1/messages` with `x-api-key` and `anthropic-version`; system prompt kept apart from untrusted data blocks; tool specs, `tool_use` and `tool_result` mapping; usage mapping; HTTP 429 → RATE_LIMITED, 529 → UNAVAILABLE, 400 → ERROR (never a false success); HTTPS required outside the loopback test flag; the key comes only from `SecretsProvider` | **NOT VERIFIED** | Secret `llm.anthropic-key` (`SDLC_SECRET_LLM_ANTHROPIC_KEY` or `/run/secrets/llm.anthropic-key`); egress to `api.anthropic.com`; a model id chosen by the owner; then `ProviderEvaluationMain` |
| **OpenAI Chat Completions API** (O-01) | **Mocked:** same loopback server | Request to `/v1/chat/completions` with bearer auth; system message apart from untrusted data; `response_format` `json_object`; text and usage mapping | **NOT VERIFIED** | Secret `llm.openai-key`; egress to `api.openai.com`; a model id; then the evaluation |
| **Provider benchmark** (O-01) | Fake provider in `ProviderEvaluationTest` (scoring logic only) | The harness ran on 2026-11-08 without keys and reported both providers **NOT_EXECUTED** (`docs/reports/model-provider-evaluation.md`) | **NOT EXECUTED.** No numbers exist; none were invented | Keys, model ids and current list prices (operator input) |
| **GitHub App** | **Mocked:** `support/FakeGitHub` on loopback | JWT exchange, narrowed installation token, scope refusal, host allowlist, redirect rules, size caps, SHA pinning, ownership clash. New: credential failures raise `GITHUB_CREDENTIAL_FAILURE` security events and alerts | **NOT VERIFIED** | App id, private key, a test organisation and repository; egress to `api.github.com` and `codeload.github.com` |
| **Security alert webhook** (C-17) | **Loopback** HTTP receiver in `SecurityAlertingIT` | HMAC-SHA256 `X-Sdlc-Signature`, content-free JSON, dedup, rate limit, bounded retries on 5xx, delivery status | **NOT VERIFIED** against a real receiver | A receiver URL (HTTPS) and a shared secret chosen by operations |
| **Security alert email** (C-17) | **Loopback** SMTP listener in `SecurityAlertingIT` (plaintext allowed only by a test flag) | SMTP dialogue, sender and recipients, content-free body, retries | **NOT VERIFIED** against a real mail server (implicit TLS on 465 not exercised against a real server) | SMTP host, port, account and recipients |
| **Dependency and SAST scanners** (O-03) | None: the kinds report **UNAVAILABLE** | That an unconfigured kind never reports success and fails the release gate | **NOT INTEGRATED.** No product chosen | Product choice and an adapter implementing `SecurityScanProvider` |
| **Secret scanner** (O-03) | Built-in, in-process | `BuiltinSecretScannerTest`; release gate in `ReviewIT` | Local implementation only (no external product) | — (optionally an external product) |
| **Managed KMS** (O-12/C-13) | None. `LocalKeyManagement` only | Envelope format and tenant binding (`EnvelopeEncryptionTest`); production refusal (jar smoke) | **NOT IMPLEMENTED** for any managed KMS | Hosting platform (O-07), then an adapter and a test against that KMS |
| **Secrets service** (O-12) | Environment and mounted files (`ConfiguredSecretsProvider`) | Name mapping and file reading (`ConfiguredSecretsProviderTest`) | File mode works with any service that mounts files (e.g. a Kubernetes or Docker secret); **no specific service verified** | The platform's secret mounting |
| **Object store** | Encrypted local file store | `EncryptedFileObjectStoreTest`, `RetentionIT` | **Development only.** No S3-compatible adapter | O-07 platform choice, then an adapter |
| **Malware scanner** | ClamAV container in CI (PR #4); fake clamd in tests | Fail-closed, quarantine, events | Development/CI only | Production hardening |
| **Extractor sandbox** (O-15) | Real kernel namespaces in this container | `ExtractorIsolationTest` (executed, not skipped) | Verified **in this container only**; production hosts must allow unprivileged user namespaces (otherwise extraction defers, fail-closed) | A check on the production host |
| **Docker Compose** | `docker compose config` with placeholder secrets: valid | Configuration only | **NOT RUN:** no Docker daemon | A Docker host |
| **Packaged jar one-shot profiles** | Real jar, real PostgreSQL 16, fresh database | `migrate` exit 0 (104 changesets); `retention apply` exit 0; `delete-tenant-data` refused; `bootstrap-ceremony-open` refused while disabled; API in production mode refused to start | Executed locally | — |
| **GitHub Actions CI** | — | Last green on PR #6 | **NOT RUN** on this branch: no push authorisation | Authorisation to push |

## Credentials and access checklist (owner to supply; never commit)

| Item | Where it goes |
|---|---|
| Anthropic API key | Secret `llm.anthropic-key`, worker only |
| OpenAI API key | Secret `llm.openai-key`, worker only |
| GitHub App id and private key | Worker configuration and a mounted secret file |
| Alert webhook URL and shared secret; SMTP account | Worker configuration (see R-04 in the security report) |
| Network egress | `api.anthropic.com`, `api.openai.com`, `api.github.com`, `codeload.github.com`, the alert hosts |

No credential was available to this session, and none was invented. No test uses a real key.
