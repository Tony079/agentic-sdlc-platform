# Incident Response

## Severity
- **SEV1:** cross-tenant data exposure, leaked key or private key, malware served to a user, or audit tampering.
- **SEV2:** platform down, all analyses failing, or GitHub/model credentials invalid.
- **SEV3:** a single requirement stuck, or a single job dead.

## First steps
1. Record the time, the correlation ids and the affected tenant ids (from `platform_security_event` / `audit_event`; content-free).
2. **SEV1 containment:**
   - Rotate the affected secret (`secrets-and-key-management.md`).
   - Disable the affected staff account: `provision disable-staff`, which takes effect on the next request.
   - Revoke grants or assignments: `provision revoke-*`.
   - To stop all outbound GitHub access, set `SDLC_GITHUB_ENABLED=false` and restart the worker.
   - To stop model calls, set `SDLC_MODEL_PROVIDER=none` and restart the worker.
3. Preserve evidence. Audit and security tables are append-only for the runtime logins; take a database snapshot before any repair.
4. Communicate per the organisation's policy. **No external notification channel exists in the product** (in-app only, AC-EXT-40).

## Known limits
- There is no administrative retry (O-27). A FAILED requirement can only be cancelled by its owner and resubmitted as a new requirement.
- There is no Platform Admin access to tenant content (O-32). Investigate with the content-free audit, or under a separately approved database procedure.
