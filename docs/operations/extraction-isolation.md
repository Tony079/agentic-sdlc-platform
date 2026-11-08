# Extraction Isolation Runbook (O-15)

Decision D-R8-11; ADR-04. Code: `docprocessing/internal/ExtractorLauncher`, `ExtractionHandler`.

## 1. What runs

Document text extraction runs in a **separate JVM subprocess** launched by the worker for each job. The subprocess:
- starts with an **empty environment**, so no database password, storage key or API key is inherited;
- works in a per-job temporary directory that is removed afterwards;
- has a maximum heap (`sdlc.documents.extraction.max-heap`, default 256m) and a wall-clock timeout (`...timeout`, default 120 s), after which it is killed;
- runs under `prlimit`, with these limits:
  - CPU seconds;
  - maximum output file size (`...max-output-bytes`, default 64 MiB);
  - at most 256 open files;
  - no core dumps;
- with `sandbox=unshare-net`, also runs inside `unshare --user --map-root-user --net`: a new user namespace and an empty **network namespace**, so it cannot reach the database, the scanner, the object store or the Internet.

## 2. Configuration (worker)

| Setting | Values | Production |
|---|---|---|
| `SDLC_DOCUMENT_EXTRACTION_ENABLED` | `false` (default) / `true` | Enable only with the sandbox required |
| `SDLC_DOCUMENTS_EXTRACTION_SANDBOX` | `none` (default) / `unshare-net` | `unshare-net` |
| `SDLC_DOCUMENTS_EXTRACTION_SANDBOX_REQUIRED` | `false` (default) / `true` | `true`. Production mode refuses to start if extraction is enabled without it (`EXTRACTION_SANDBOX_NOT_REQUIRED`) |

**Fail-closed behaviour:**
- At startup the launcher runs a probe inside the sandbox.
- If the probe fails while the sandbox is required, every extraction job **defers** with `EXTRACTION_SANDBOX_UNAVAILABLE` (no retry budget used). The document waits; nothing is extracted unsandboxed.
- A required sandbox with `sandbox=none`, or an unknown sandbox value, is rejected at startup.

## 3. Host requirements

- `prlimit` and `unshare` (util-linux) in the worker image.
- Unprivileged user namespaces enabled on the host kernel (`kernel.unprivileged_userns_clone=1` where that setting exists). Some container runtimes block them through seccomp or AppArmor. If they are blocked, extraction defers instead of running unsandboxed. **Check this on the production host before enabling extraction.**

## 4. Residual risks (not mitigated)

| Risk | Why it remains | Mitigation path |
|---|---|---|
| The extractor can **read** files that the worker's OS user can read (no mount namespace) | Only user and network namespaces are used | Run the worker as a dedicated user with no readable secrets on disk (secrets via mounted files readable only by the main process), or add a mount namespace or separate container |
| No seccomp filter | Not implemented | Container-level seccomp profile |
| A parser vulnerability gives code execution inside the subprocess | Inherent to parsing untrusted documents | The limits above bound the impact; there is no network to exfiltrate through |
| The CPU and file-count limits are configured but not asserted by a test | Test covers network, environment and file size | Add tests if required |

## 5. Verification

| Check | Result (2026-11-08) |
|---|---|
| `ExtractorIsolationTest` (2) | Pass, **executed, not skipped**, in this container. Outside the sandbox the probe has network; inside it has none; the environment is empty; the file-size limit holds; a required sandbox is never skipped |
| Extraction integration tests | All run with `sandbox=unshare-net` and `sandbox-required=true` |
| Production host | **Not verified** |
