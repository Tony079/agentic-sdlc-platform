# Security Scanning and Release Gate Runbook (O-03)

Decision D-R8-09; ADR-06. Code: `securityscan` module.

## 1. Flow

1. When a repository snapshot is indexed (`RepositorySnapshotIndexed`), the worker enqueues one `SECURITY_SCAN` job per snapshot (idempotent).
2. The job runs every scanner provider against the snapshot content. It records one `security_scan_run` per provider (status COMPLETED, FAILED or UNAVAILABLE) with its normalised `security_finding` rows:
   - rule id;
   - severity: CRITICAL, HIGH, MEDIUM, LOW or INFO;
   - file and line;
   - masked evidence;
   - fingerprint.
3. The release gate (manual release, O-16) reads only the recorded runs for the snapshot's commit. It passes for a kind only when:
   - a COMPLETED run exists;
   - no finding is at or above the blocking severity.

## 2. Providers

| Kind | Provider | Status |
|---|---|---|
| SECRET | `BuiltinSecretScanner` (`sdlc.security-scan.secret-provider=builtin`, default; `none` disables it) | Implemented. Pattern rules: `private-key` (CRITICAL); `aws-access-key-id`, `github-token`, `slack-token`, `google-api-key` (HIGH); `generic-password-assignment` (MEDIUM). Evidence is masked (never the full secret); the fingerprint lets an operator track a finding without seeing it |
| DEPENDENCY | `UnconfiguredScanProvider` | **UNAVAILABLE**: no product named under O-03 |
| SAST | `UnconfiguredScanProvider` | **UNAVAILABLE**: no product named under O-03 |

An UNAVAILABLE or FAILED run never counts as a pass.

## 3. Gate configuration

| Setting | Default | Meaning |
|---|---|---|
| `SDLC_RELEASE_REQUIRED_SCAN_KINDS` | `SECRET` | Comma-separated kinds that must pass. Adding DEPENDENCY or SAST before a product is integrated blocks **every** release, by design |
| `SDLC_RELEASE_BLOCKING_SEVERITY` | `HIGH` | Findings at or above this block the release |

The feature flag `release.security-scan-gate` is **protected**: it cannot be switched off through the operations API.

## 4. Adding a product

1. Implement `securityscan/api/SecurityScanProvider` for the kind: map the tool's output to normalised findings; mask evidence; return UNAVAILABLE on any tool error, never an empty COMPLETED result.
2. Register it in `SecurityScanHandler.securityScanProviders` behind a configuration value.
3. Add a test with a known-vulnerable fixture and a test that a tool failure yields UNAVAILABLE.
4. Add the kind to `SDLC_RELEASE_REQUIRED_SCAN_KINDS`.

## 5. When a release is blocked

- The API answers 409 `RELEASE_GATE_FAILED` with the failed gate names, for example `SECURITY_SCAN_SECRET`.
- An audit row and a `RELEASE_GATE_FAILED` security event (alerted, MEDIUM) are recorded.
- Review the findings for the snapshot; a fix needs a new snapshot, analysis and approval.

**There is no override.** That is deliberate: no person or model can waive a failed scan.

## 6. Verification

| Check | Result (2026-11-08) |
|---|---|
| `BuiltinSecretScannerTest` (2) | Pass: detection, masking, fingerprint stability |
| `ReviewIT.releaseNeedsAReleaseManagerAndPassingGates` | Pass: the release is blocked with `SECURITY_SCAN_SECRET` until a passing scan exists |
| Real dependency or SAST tool | **Not integrated** |
