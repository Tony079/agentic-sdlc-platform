# Recovery Runbook

| Symptom | Check | Action |
|---|---|---|
| Worker not processing | Liveness file age; logs `Job worker ... started` | Restart the worker. Leases expire and jobs are re-claimed with a raised epoch (no duplicate commit) |
| Outbox backlog grows | Dispatcher errors in the worker logs | Fix the cause and restart. Events retry with backoff up to 10 attempts, then go DEAD |
| Repository stays PENDING_VERIFICATION | `SDLC_GITHUB_ENABLED`, App id, key file; REPO_VERIFY `last_error_code` | Configure GitHub and restart the worker. Waiting jobs resume (deferral) |
| Requirement waits in AWAITING_DOCUMENTS | Document states (`document_version`), snapshot status | Scanner up (QUARANTINED retries); a document needing a decision blocks analysis until a Business Reviewer excludes it (reviewer inbox, T12/T13) or the owner acknowledges an UPLOAD_FAILED exclusion; a FAILED snapshot blocks analysis (D-M4R-16) |
| Requirement waits in ANALYSING | BA job `last_error_code` | `MODEL_NOT_CONFIGURED`: configure a provider (O-01). `MODEL_RATE_LIMITED`/`MODEL_UNAVAILABLE`: waits automatically |
| Requirement FAILED (T09/T19) | `job.last_error_code`, `requirement_repository.failure_code` | Analysis failures (T19): fix the cause, then a staff member with `JOB_RETRY` retries the dead BA job through T26 (`release-and-retry-operations.md` §2). Permanent failures (FATAL, `COST_CAP`) and repository failures (T09) have no retry path (PC-01 not approved): the owner cancels (T29) and submits a new requirement |
| Requirement in AWAITING_CLARIFICATION | Open clarifications (`app.clarification`, status OPEN/NEEDS_INFO) | The assigned person answers in the inbox (owner: `/clarifications`; reviewer: `/staff/inbox`). The worker then resumes (T17). There is no escalation or expiry yet (FR-CLR-07, P1) |
| Retention run failed | Non-zero exit of `retention apply`; its log | Fix the cause and rerun. The run is idempotent; rows already marked DELETED/PURGED are skipped |
| Requirement stuck in INTERNAL_APPROVED | Release queue (`/api/v1/staff/releases`); audit `RELEASE_GATE_FAILED` | Release is manual (O-16): a `RELEASE_APPROVE` holder must release it. If a gate failed (`security-scanning.md` §5) or the inputs changed (version STALE), the version cannot be released; cancel and resubmit (no re-analysis path from INTERNAL_APPROVED) |
| Database restored from backup | — | Follow `database-backup-and-restore.md`, then check the outbox drains and the workers resume |
| Staff member lost their authenticator | — | Two administrators run `provision reset-mfa`. The member re-enrols within the new window. There is no MFA bypass |
