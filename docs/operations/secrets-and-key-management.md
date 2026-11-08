# Secrets and Key Management (O-12/C-13)

No secret is committed. `.env.example` holds placeholders only. gitleaks runs over the full history; reviewed false positives are listed by exact fingerprint in `.gitleaksignore`.

## 1. Secrets provider

Application secrets that are not database or storage keys are read through `SecretsProvider` (`common/api`), by name:

| `sdlc.secrets.provider` | Source for secret `a.b-c` | Use |
|---|---|---|
| `env` (default) | Environment variable `SDLC_SECRET_A_B_C` | Development and test |
| `file` | File `/run/secrets/a.b-c` (directory `sdlc.secrets.directory`) | **Production.** Production mode refuses `env` (`SECRETS_PROVIDER_NOT_MANAGED`) |

The file mode works with any platform that mounts secrets as files (Docker or Kubernetes secrets, or a vault agent). No specific secrets service has been verified.

| Secret name | Used by |
|---|---|
| `llm.anthropic-key` | worker (Anthropic adapter, O-01) |
| `llm.openai-key` | worker (OpenAI adapter, O-01) |

## 2. Encryption keys

| Key | Used by | Format | Rotation |
|---|---|---|---|
| `SDLC_STORAGE_KEY` (key-encryption key for `LocalKeyManagement`) | api, worker, retention | `openssl rand -base64 32` | New objects use envelope format **SDE2**: a fresh data key per object, wrapped by this key with the tenant and object key as associated data. Older **SDE1** objects (encrypted directly with the key) remain readable. **No re-wrap command exists yet** (R-05). Losing the key makes stored content unreadable |
| Managed KMS | — | — | **Not implemented.** `sdlc.storage.kms.provider` accepts only `local`; any other value refuses to start. Production mode refuses `local` (`STORAGE_KMS_LOCAL`). Production therefore needs a KMS adapter for the chosen platform (O-07) |
| `SDLC_MFA_KEY` | api, provision | `openssl rand -base64 32` | No re-encryption tool. Rotation requires every staff member to re-enrol (two-person `reset-mfa`) |

## 3. Other credentials

| Secret | Used by | Notes |
|---|---|---|
| `POSTGRES_PASSWORD` | database bootstrap only | Superuser; not used by the application |
| `SDLC_DB_MIGRATOR_PASSWORD`, `_APP_`, `_WORKER_`, `_PROVISIONER_`, `_RETENTION_` | one process each | `openssl rand -hex 24`; rotate with `ALTER ROLE ... PASSWORD` and restart that process |
| GitHub App private key | worker | Mounted file, `SDLC_GITHUB_PRIVATE_KEY_FILE`; installation tokens per job, in memory |
| Alert webhook secret, SMTP password | worker | Spring properties `sdlc.alerts.webhook.secret`, `sdlc.alerts.email.password`. **Not yet read through `SecretsProvider` (R-04):** in production supply them from a mounted file via Spring's `configtree:` import, not plain environment variables |
| Production bootstrap authorisation | operator, at ceremony time | Only its SHA-256 is configured; the value is typed on stdin and never stored |
| `SDLC_DEV_SEED_PASSWORD` | migrate (development only) | Never enable the seed outside development |

## 4. Rules

- Pass secrets through the platform's secret store as files. Never put them in images, Compose files in Git, CI logs, test fixtures or the frontend bundle.
- Secrets are never logged. The production validator reports only the names of failed checks.
- TOTP codes for provisioning and the ceremony are command arguments (SR-M4-2). Run them from a trusted host and clear the shell history.
