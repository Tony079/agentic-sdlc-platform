# Security Verification Report (2026-11-08)

| | |
|---|---|
| Scope | Branch `feature/mvp-production-readiness` (local) |
| Type | Self-review plus automated tests. **This is not an independent security review or a penetration test.** |
| Code tested | Commit `ea7b833` and later documentation commits |

## 1. Controls with executed evidence

| Control (PRD / TDD) | Evidence (executed 2026-11-08) |
|---|---|
| Forced RLS on every tenant table; no SECURITY DEFINER; no PUBLIC EXECUTE; per-login column grants | `SchemaSecurityIT` (9), now including `clarification`, `clarification_message`, `document_decision` and `retention_policy` |
| No tenant-context leak on pooled connections | `RlsPoolLeakIT`, `RlsIsolationIT` |
| Jobs set the tenant before data access; lease fencing | `JobQueueIT` (13) |
| Cross-tenant requests: 404, recorded | `RequirementVisibilityIT`, `DocumentApiIT`, `LinkRequestIT`, `RepositoryIntegrationIT`, `ReviewIT`, `ClarificationIT` |
| Staff identity: TOTP MFA with replay guard, server-held active tenant gated by assignment, re-check on every request | `StaffAuthIT` (7), `TotpTest`, `MfaCipherTest` |
| Two-person capability grants (S-02); runtime logins cannot grant | `StaffProvisioningIT` (11) |
| **S-01:** only `sdlc_retention` can delete audit rows; `sdlc_app` and `sdlc_worker` are refused | `RetentionIT.expiredAuditRowsAreDeletedOnlyByTheRetentionLogin` |
| Retention login is narrow (no content columns, no business writes, RLS applies) | `RetentionIT.retentionLoginIsNarrowAndRespectsTenantIsolation` |
| Deletion is audited without content (FR-RET-04) | `RetentionIT` |
| Clarification access: owner only on the client side; role-checked on the staff side; the model never answers | `ClarificationIT` |
| Four-eyes approval, approval bound to content and inputs, approvals invalidated on input change | `ReviewIT`, `ClarificationIT` |
| Client BRD view without source excerpts | `ReviewIT` |
| Evidence integrity (forged or foreign citations) | `EvidenceVerifierTest`, `AnalysisIT` |
| Prompt injection: data never mixed with instructions; output cannot act | `AnalysisIT` |
| **Tool calling:** only tools in the job's registry run; others refused with fixed text; results capped and returned as data; loops bounded; arguments and results never logged | `ModelGatewayIT` (5 new tests) |
| GitHub: token narrowed to one repository, read-only scopes, host allowlist, redirect rules, size caps | `GitHubBrokerTest` |
| Archive limits and path traversal; XXE-safe XML | `SnapshotArchiveTest`, `IndexerTest` |
| Upload validation and malware handling | `DocumentApiIT`, `DocumentContentValidatorTest`, `ClamdScannerTest` |
| Encryption at rest of stored objects (development store) | `EncryptedFileObjectStoreTest` |
| Secret scanning | gitleaks 8.21.2 over the full history (52 commits): no leaks. Three reviewed false positives are listed by fingerprint in `.gitleaksignore` |
| Frontend dependencies | `npm audit --omit=dev`: 0 vulnerabilities. Development tooling: 10 moderate (`minimatch` via ESLint), not fixed |

## 2. New code reviewed in this phase

| Area | Review result |
|---|---|
| Retention (`RetentionService`, changeset 0023) | Separate login; DELETE granted only on derived tables and `audit_event`. Guards allow only DELETED/PURGED final states and a `{"purged": true}` proposal marker, and only for `sdlc_retention`. Object-store deletion failure is logged without the key. A tenant deletion requires the slug to be repeated. **Residual:** the process runs with the storage key (it must delete encrypted objects). |
| Version diff endpoint | Reviewer capability required. Both versions must belong to the same requirement (otherwise 404). Read-only. Audited as BRD_VIEWED. |
| Clarification and decision UI | Uses only the existing server-checked endpoints. No client-side authority. Error messages are fixed text. |
| Tool calling | The model cannot add tools. Names are validated by pattern. An unknown name is labelled `unknown`. A tool exception reaches the model only as "FAILED" (no exception text). Limits: 10 rounds, 40 calls. Results are UTF-8-safe truncated. **Residual:** a tool implementation is trusted code and must enforce its own tenant and snapshot scope; no production tool exists yet. |

## 3. Open findings and residual risks

| ID | Finding | Status |
|---|---|---|
| SEC-REVIEW-1 / O-15 | The extractor subprocess has OS-level isolation only (no separate container, no seccomp). Extraction must stay off for real client documents | OPEN (owner decision) |
| SEC-REVIEW-2 / SR-M4-4 / O-12 / C-13 | `SDLC_STORAGE_KEY` and `SDLC_MFA_KEY` are single static keys from the environment, with no rotation and no KMS | OPEN, **production blocker** |
| SEC-REVIEW-3 / O-03 | ClamAV image is not hardened; it is the development and CI scanner only | OPEN |
| SR-M4-1 | JavaParser runs in the worker JVM; a timed-out parse is abandoned, not killed | Residual |
| SR-M4-2 | Provisioning TOTP codes appear in command arguments | Known limit (D-M4R-09) |
| SR-M4-3 | Login and MFA throttles are in memory, per instance | Residual (D-M1-02) |
| O-13 | Production administrator ceremony not implemented. `bootstrap-admins` is refused outside development/test, so production staff cannot be provisioned | OPEN, **production blocker** |
| O-32 / AC-EXT-37 | No admin access-grant path to tenant content (by design, staff have no automatic access) | OPEN (owner decision) |
| C-17 | Security events are recorded, but no alert channel exists (malware, repeated cross-tenant attempts) | OPEN, **production blocker** for AC-EXT-33 "admins are alerted" |
| SEC-NEW-1 | Retention runs with the storage key and the retention database password. Compromise of that host allows deletion (not reading of content columns) | Residual; schedule it on a restricted runner |
| SEC-NEW-2 | The support check is lexical (D-R5-07). A misleading statement can pass with a matching quote; human review is the control | Residual (by design) |

## 4. Not performed

- An independent security review or a penetration test.
- A container image scan (Trivy or similar).
- A Maven dependency vulnerability scan (OWASP Dependency-Check or similar). It was not run because the environment is offline for that database.
- A backup and restore drill.
- A production platform check of RLS (O-31).
- Real-provider prompt-injection testing (no provider, O-01).

## 5. Assessment

The application-level controls the PRD requires for tenant isolation, separation of duties, evidence integrity and audit immutability are implemented and covered by executed tests.

**The system is not production-secure yet.** The production blockers above remain:
- key management (O-12/C-13);
- the administrator ceremony (O-13);
- the alert channel (C-17);
- extractor isolation (O-15);

and an independent review has not been done.
