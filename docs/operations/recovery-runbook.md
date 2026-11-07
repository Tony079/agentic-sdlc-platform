# Recovery Runbook

| Symptom | Check | Action |
|---|---|---|
| Worker not processing | Liveness file age; logs `Job worker ... started` | Restart the worker. Leases expire and jobs are re-claimed with a raised epoch (no duplicate commit) |
| Outbox backlog grows | Dispatcher errors in the worker logs | Fix the cause and restart. Events retry with backoff up to 10 attempts, then go DEAD |
| Repository stays PENDING_VERIFICATION | `SDLC_GITHUB_ENABLED`, App id, key file; REPO_VERIFY `last_error_code` | Configure GitHub and restart the worker. Waiting jobs resume (deferral) |
| Requirement waits in AWAITING_DOCUMENTS | Document states (`document_version`), snapshot status | Scanner up (QUARANTINED retries); a document needing a decision blocks analysis (T12/T13 not built: the owner may remove it in DRAFT only, or cancel); a FAILED snapshot blocks analysis (D-M4R-16) |
| Requirement waits in ANALYSING | BA job `last_error_code` | `MODEL_NOT_CONFIGURED`: configure a provider (O-01). `MODEL_RATE_LIMITED`/`MODEL_UNAVAILABLE`: waits automatically |
| Requirement FAILED (T09/T19) | `job.last_error_code`, `requirement_repository.failure_code` | No retry path (O-27). The owner cancels (T29) and submits a new requirement once the cause is fixed (for example the App installed) |
| Requirement stuck in INTERNAL_APPROVED | audit `BRD_RELEASE_BLOCKED` | Inputs changed after approval: the version is STALE. Cancel and resubmit (no re-analysis path from INTERNAL_APPROVED) |
| Database restored from backup | — | Follow `database-backup-and-restore.md`, then check the outbox drains and the workers resume |
| Staff member lost their authenticator | — | Two administrators run `provision reset-mfa`. The member re-enrols within the new window. There is no MFA bypass |
