## Summary

<!-- What changes and why. Link the milestone. -->

## PRD / TDD traceability

<!-- PRD requirement or acceptance-criterion IDs this advances (never "complete" unless fully verified). -->

## Decision register

<!-- New or affected entries in docs/decisions/decision-register.md (open decisions, conflicts, security findings). -->

## Security checklist

- [ ] Tenant comes from the authenticated session only; tenant-owned tables have forced RLS
- [ ] No new privilege for `sdlc_app`; audit trail stays append-only
- [ ] No secrets, credentials or business content in code, logs, audit or security events
- [ ] Workflow/approval rules enforced on the server, not only in the UI

## Tests

<!-- Commands run and results. Do not report tests that were not run. -->
