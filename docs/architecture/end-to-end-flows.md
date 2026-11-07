# End-to-End Flows (as built)

Each flow lists the transactions and evidence. Test names refer to `backend/src/test`.

## 1. Requirement to verified BRD (`AnalysisIT`, `RepositoryIntegrationIT`)

1. **Client User:** creates the requirement and project, attaches documents, issues a one-time repository link code (C-18).
2. **Client Approver:** redeems the code with the GitHub URL. This is the client authorisation (Q-A).
3. **Staff** (password + TOTP, assigned tenant, `REPOSITORY_REGISTER`): registers the link. The outbox carries `RepositoryRegistered`.
4. **Client:** submits (T01). The worker routes (T02) and enqueues REPO_VALIDATE.
5. **Worker:**
   - REPO_VERIFY (broker: App installation, scoped read-only token, repository id) marks the repository VERIFIED.
   - REPO_VALIDATE pins the default-branch head SHA (T06).
   - REPO_SNAPSHOT stores the archive encrypted, with the pax SHA checked and limits applied.
   - REPO_INDEX records symbols and dependencies, then publishes `RepositorySnapshotIndexed`.
6. **Documents:** DOCUMENT_SCAN (ClamAV), then DOCUMENT_EXTRACT (isolated extractor), then `DocumentProcessed`.
7. **Guard 9.4** (`analysis-guard` consumer), on every input event:
   - When everything is ready, the input set is frozen and hashed.
   - T11 follows, with a BA_ANALYSIS stage and job.
8. **BA_ANALYSIS (outside any transaction):**
   - Phase A (`ba.phase-a@1`) selects relevant files.
   - Phase B (`ba.phase-b@1`) proposes BRD items with citations, through the model gateway.
   - Tx P stores the pending result and enqueues BA_COMMIT_RESULT.
9. **BA_COMMIT_RESULT (Tx C):**
   - Checks: the stage is current, the state is ANALYSING, the input hash is unchanged (otherwise STALE, then T15 or T14), and the schema is valid.
   - Citations are verified; failed FACTs become ASSUMPTION and unsupported statements are FLAGGED.
   - Writes the BRD version, then T18 and T20 (INTERNAL_REVIEW).

## 2. Review and release (`ReviewIT`)

1. **Business Reviewer** (staff, `BUSINESS_REVIEWER`, assigned tenant):
   - opens the queue and the version, with quotes;
   - acknowledges every FLAGGED item;
   - approves with the content hash shown.

   The API then: checks the hashes and inputs and enforces four-eyes; records an immutable approval; sets the version to APPROVED_INTERNAL; performs T21 and publishes `BrdApprovedInternal`.
2. **Worker (separate transaction):** re-checks the version, the approval and the inputs, then T25 (SHARED_WITH_CLIENT), sets the version to RELEASED and notifies the owner in the app.
3. **Owner:** reads the released BRD (references, no excerpts). They may request changes (T27). The worker then runs T28 with a new input set, which leads to v2. v1 becomes SUPERSEDED and its approval INVALIDATED.
4. **Alternatives:**
   - Request revision with notes (T22), then T24 re-analysis with the notes; the note author cannot approve the next version.
   - Reject (T23), which is terminal; the version becomes WITHDRAWN.

## 3. Failure paths

| Situation | Behaviour | Test |
|---|---|---|
| GitHub App not installed | ACCESS_DENIED, then REPO_VALIDATE fatal, then T09 FAILED | `RepositoryIntegrationIT` |
| Repository already owned by another tenant | UNAVAILABLE (generic), then T09 | `RepositoryIntegrationIT` |
| Link not registered yet | REPO_VALIDATE defers (no retries used) | `RepositoryIntegrationIT` |
| Model output invalid three times | BA job DEAD, then T19 FAILED | `AnalysisIT` |
| No model provider configured (default) | BA job waits (deferral) | `AnalysisIT` (mapping) |
| Budget cap | COST_CAP, fatal, then T19 | mapping test |
| Document deleted during analysis | Tx C rejects (REJECTED_STALE), stage STALE, T15 re-run | `AnalysisIT` |
| Scanner down | Document stays QUARANTINED; the guard waits | `DocumentApiIT` |
| Worker crash mid-job | Lease expiry, re-claim with fencing; one result | `JobQueueIT` |
