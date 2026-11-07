# Secrets and Key Management

No secret is committed. `.env.example` holds placeholders only. gitleaks runs in CI over the full history; reviewed
false positives are listed by exact fingerprint in `.gitleaksignore`.

| Secret | Used by | Generation | Rotation (current state) |
|---|---|---|---|
| `POSTGRES_PASSWORD` | db bootstrap only | random | Superuser; not used by the application |
| `SDLC_DB_MIGRATOR_PASSWORD` | migrate | `openssl rand -hex 24` | `ALTER ROLE ... PASSWORD`, then redeploy migrate |
| `SDLC_DB_APP_PASSWORD` | api | as above | As above; rolling restart of the API |
| `SDLC_DB_WORKER_PASSWORD` | worker | as above | As above |
| `SDLC_DB_PROVISIONER_PASSWORD` | provision only | as above | As above; never give it to the API, worker or agents |
| `SDLC_STORAGE_KEY` | api, worker | `openssl rand -base64 32` (AES-256-GCM) | **No rotation support** (single static key, SEC-REVIEW-2, O-12/C-13). Losing it makes stored documents and snapshots unreadable. A KMS with key versioning is required before production |
| `SDLC_MFA_KEY` | api, provision | `openssl rand -base64 32` (AES-256-GCM, staff id as associated data) | **No re-encryption tool.** Rotating it requires every staff member to re-enrol (two-person `reset-mfa`) |
| GitHub App private key | worker only | Created in the GitHub App settings | Mount as a file and set `SDLC_GITHUB_PRIVATE_KEY_FILE`. Rotate in GitHub, replace the file, restart the worker. Installation tokens are per job, in memory, never stored |
| Model provider credentials | worker (gateway) | — | No provider adapter exists yet (O-01) |
| `SDLC_DEV_SEED_PASSWORD` | migrate (development only) | random | Never enable the seed outside development |

Rules:
- Pass secrets through the orchestrator's secret store (files or environment), never through images, Compose files in Git, CI logs or the frontend bundle.
- Staff TOTP codes appear as `provision` command arguments (D-M4R-09). Run provisioning from a trusted host and clear the shell history.
