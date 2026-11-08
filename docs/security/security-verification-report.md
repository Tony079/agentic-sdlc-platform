# Security Verification Report (Deliverable D, 2026-11-08)

| | |
|---|---|
| Scope | Branch `feature/mvp-production-readiness` (local), including the owner-decision implementation (`5afb9a8` to `98a8c8d`) |
| Type | Self-review plus automated tests. **This is not an independent security review or a penetration test.** |
| Test run | Full backend suite on `df3743d`: 296 tests, 0 failures. `ManualIntakeIT` on `98a8c8d`: 3/3 |

## 1. Controls with executed evidence

| Control | Evidence (executed 2026-11-08) |
|---|---|
| Forced RLS on every tenant table; no SECURITY DEFINER; no PUBLIC EXECUTE; exact per-login grants | `SchemaSecurityIT` (9). Its exact list of RLS-protected tenant tables now includes `requirement_closure`, `release_approval`, `security_scan_run`, `security_finding`, `change_request`, `change_request_diff`, `approved_scope_version` and `admin_retry_request`. The new global tables (`tenant_lifecycle_request`, `staff_bootstrap_ceremony`, `feature_flag`, `feature_flag_change`, `security_alert*`) have no `tenant_id` column, so they fall outside that check; their guards are tested by `TenantLifecycleIT`, `BootstrapCeremonyIT`, `OperationsIT` and `SecurityAlertingIT` |
| No tenant-context leak on pooled connections; jobs set the tenant; lease and epoch fencing | `RlsPoolLeakIT`, `RlsIsolationIT`, `JobQueueIT` (14) |
| The API login cannot touch the job queue | `JobQueueIT.apiLoginCannotClaimOrForgeJobs`. Operator retry reads only the content-free `app.dead_job` view |
| **O-13:** production bootstrap needs a two-person, time-limited, single-use ceremony | `BootstrapCeremonyIT` (2): open needs enablement and the configured authorisation hash; one OPEN ceremony; both approvals with own password and TOTP; grants bound to the ceremony; reuse and expiry refused; the database refuses bootstrap grants without a completed ceremony. Jar smoke: refused while disabled |
| **O-25:** tenant soft deletion is two-person and database-guarded; nothing is physically deleted | `TenantLifecycleIT` (2): a single-person request and a direct status update are refused by triggers; after deletion, client sessions are refused and queued jobs cancelled; data rows and objects remain; restoration is two-person |
| Retention login is narrow; audit rows deletable only by `sdlc_retention` (S-01); DELETED tenants never purged | `RetentionIT` (5) |
| Closure record is immutable and written only by the worker | `ManualIntakeIT` (runtime logins refused on UPDATE and DELETE) |
| **O-16:** release separation of duties and binding | `ReviewIT.releaseNeedsAReleaseManagerAndPassingGates`: without `RELEASE_APPROVE` the request is refused; the internal approver is refused (trigger); a wrong content hash or commit SHA is refused; a failing gate is audited and alerted |
| **O-03:** no false success from scanners | Unconfigured kinds return UNAVAILABLE, which fails the gate; the built-in secret scanner masks evidence (`BuiltinSecretScannerTest`) |
| **O-27:** no generic or cross-tenant retry; permanent failures never retried | `OperatorRetryIT` (2): `JOB_RETRY` plus a tenant assignment are required; only dead BA jobs of FAILED requirements; FATAL and `COST_CAP` refused. `JobQueueIT`: retry window ends in DEAD |
| **O-08:** a flag change needs a different operator's acknowledgement; protected flags immutable | `OperationsIT` |
| **C-09:** client approver separation and C-18 disclosure | `ReviewIT`: the assessor cannot review; the submitter cannot accept; the approver sees only the change-request view |
| **C-17:** alerts carry no content; deduplicated; rate-limited; retried within bounds | `SecurityAlertingIT` (3), with a loopback HTTP webhook (allowed only by a test flag; HTTPS is required otherwise) and a loopback SMTP listener |
| **O-15:** extractor without network or environment; required sandbox never skipped | `ExtractorIsolationTest` (2, executed); every extraction test runs with `sandbox-required=true` |
| **O-12:** per-object data keys; tenant-bound associated data; production refuses insecure configuration | `EnvelopeEncryptionTest` (4), `EncryptedFileObjectStoreTest` (7), `ProductionConfigurationValidatorTest` (3); jar smoke refused production start |
| **O-01:** provider keys only from the secrets provider; HTTPS only | `HostedProviderAdaptersTest` (3, mock): keys sent only in the provider's auth header; plain HTTP refused without the loopback test flag |
| Staff identity, MFA, two-person grants (S-02); no automatic tenant access | `StaffAuthIT` (7), `StaffProvisioningIT` (11); every new staff action checks the capability **and** an assignment for the target tenant |
| Earlier controls (evidence integrity, prompt injection, tool calling, GitHub token narrowing, archive limits, upload validation) | Unchanged and still passing: `EvidenceVerifierTest`, `AnalysisIT`, `ModelGatewayIT`, `GitHubBrokerTest`, `SnapshotArchiveTest`, `DocumentApiIT` |
| Secret scanning | gitleaks 8.21.2: full history (65 commits, through `98a8c8d`) and the documentation tree before the final documentation commit: no leaks |
| Frontend dependencies | `npm audit --omit=dev`: 0. Development tooling: 10 moderate (ESLint `minimatch`), not fixed |

## 2. Review of new code (self-review)

| Area | Result |
|---|---|
| Authorisation | Every new staff endpoint calls `StaffAccess` with a capability. Tenant-scoped actions also require an assignment for that tenant (`requireCapabilityForTenant`). Platform actions (flags) require `requirePlatformCapability`. No endpoint grants staff access to all tenants or to source code |
| Database guards | Two-person rules, SoD, immutability and state finality are enforced by triggers as well as in Java, so a compromised API process cannot bypass them with plain SQL. Grants are column-level where the API writes |
| LLM involvement | No authorisation, release, retry, deletion or alerting decision involves the model |
| Secrets | No key or password appears in code, configuration defaults, tests or logs. Test keys are random per run. Ceremony authorisation and passwords are read from stdin |
| Logging | Alerts and audit rows contain identifiers, types and counts only. The scanner stores masked evidence and a fingerprint |

## 3. Open findings and residual risks

| ID | Finding | Severity | Status |
|---|---|---|---|
| R-01 | **No managed KMS or production object-store adapter.** Only `LocalKeyManagement` exists; production mode refuses to start | High | **Production blocker** (needs O-07 platform choice) |
| R-02 | Extractor sandbox has no mount namespace, seccomp or separate container; it can read files readable by the worker's user | Medium | Residual; accept or extend before real client documents |
| R-03 | Dependency and SAST scanners not integrated (no product named) | Medium | Open; the gate fails closed if those kinds are required |
| R-04 | `sdlc.alerts.webhook.secret` and `sdlc.alerts.email.password` are Spring properties, not read through `SecretsProvider`. They can be supplied from a mounted file through Spring's configuration tree, but the production validator does not enforce it | Low | Open; small follow-up |
| R-05 | Key rotation: SDE2 allows re-wrapping data keys, but no rotation command exists. `SDLC_MFA_KEY` is still a single static key | Medium | Open |
| R-06 | No legal-hold mechanism; retention deletes on schedule | Medium (legal) | Open; needs a legal decision |
| SR-M4-1 | JavaParser runs in the worker JVM; a timed-out parse is abandoned, not killed | Low | Residual |
| SR-M4-2 | Provisioning and ceremony TOTP codes pass as command arguments | Low | Known limit |
| SR-M4-3 | Login and MFA throttles are per instance, in memory | Low | Residual |
| SEC-NEW-1 | The retention process holds the storage key and its database password | Low | Residual; run on a restricted runner |
| SEC-NEW-2 | Evidence support check is lexical | Low | By design; human review is the control |
| O-32 | No admin access-grant path to tenant content | — | BLOCKED (owner decision); staff have no automatic access |

**Closed since the previous report:**
- O-13 ceremony;
- C-17 alert channel (mechanism);
- O-15 isolation (partial depth: R-02);
- physical tenant deletion replaced by soft deletion;
- SEC-REVIEW-2 reduced to R-01 and R-05.

## 4. Not performed

- An independent security review or penetration test.
- A container image scan.
- A Maven dependency vulnerability scan (the scanner database is unreachable offline).
- Real-provider prompt-injection testing (no credentials).
- A production RLS platform check (O-31).
- A backup and restore drill.

## 5. Assessment

The application-level controls for the approved decisions are implemented and covered by executed tests, with database-level enforcement for the two-person and separation-of-duties rules.

**The system is not production-secure.** The reasons:
- R-01: no managed KMS, so production cannot start;
- no independent review or image and dependency scans;
- no real external integration verified.
