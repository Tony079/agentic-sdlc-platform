package io.github.tony079.sdlc.outbox.api;

import java.util.Map;

/**
 * Event types in use and their current payload version (TDD 11.5). Consumers accept the current and the
 * previous version. The database CHECK holds the full Release 1 catalogue.
 */
public final class EventTypes {

    public static final String REQUIREMENT_SUBMITTED = "RequirementSubmitted";
    public static final String REPO_VALIDATION_STARTED = "RepoValidationStarted";
    public static final String MANUAL_INTAKE_ENTERED = "ManualIntakeEntered";
    public static final String MANUAL_INTAKE_RESUMED = "ManualIntakeResumed";
    public static final String REQUIREMENT_CANCELLED = "RequirementCancelled";
    public static final String JOB_DEAD = "JobDead";
    public static final String DOCUMENT_UPLOADED = "DocumentUploaded";
    public static final String DOCUMENT_SCANNED = "DocumentScanned";
    public static final String DOCUMENT_FAILED = "DocumentFailed";
    public static final String DOCUMENT_PROCESSED = "DocumentProcessed";
    public static final String REPO_VALIDATED = "RepoValidated";
    public static final String DOCUMENT_DECISION_REQUESTED = "DocumentDecisionRequested";
    public static final String WORKFLOW_FAILED = "WorkflowFailed";
    public static final String REPOSITORY_REGISTERED = "RepositoryRegistered";
    public static final String REPOSITORY_SNAPSHOT_INDEXED = "RepositorySnapshotIndexed";
    public static final String ANALYSIS_STARTED = "AnalysisStarted";
    public static final String EXECUTION_STALE = "ExecutionStale";
    public static final String BRD_VERSION_CREATED = "BrdVersionCreated";
    public static final String REVIEW_QUEUED = "ReviewQueued";
    public static final String BRD_APPROVED_INTERNAL = "BrdApprovedInternal";
    public static final String REVISION_REQUESTED = "RevisionRequested";
    public static final String REQUIREMENT_REJECTED = "RequirementRejected";
    public static final String BRD_RELEASED_TO_CLIENT = "BrdReleasedToClient";
    public static final String CLIENT_CHANGES_REQUESTED = "ClientChangesRequested";
    public static final String APPROVAL_INVALIDATED = "ApprovalInvalidated";
    public static final String CLARIFICATION_OPENED = "ClarificationOpened";
    public static final String CLARIFICATION_ANSWERED = "ClarificationAnswered";
    public static final String DOCUMENT_DECISION_RECORDED = "DocumentDecisionRecorded";
    public static final String RELEASE_APPROVED = "ReleaseApproved";
    public static final String ADMIN_RETRY_REQUESTED = "AdminRetryRequested";
    public static final String ADMIN_RETRY_COMPLETED = "AdminRetryCompleted";
    public static final String ADMIN_RETRY_REJECTED = "AdminRetryRejected";
    public static final String CHANGE_REQUEST_SUBMITTED = "ChangeRequestSubmitted";
    public static final String CHANGE_REQUEST_ACCEPTED = "ChangeRequestAccepted";
    /** GD-20: a project's repository or environment branches changed; the worker verifies them (ENV_VERIFY). */
    public static final String ENVIRONMENT_BRANCHES_CHANGED = "EnvironmentBranchesChanged";
    public static final String BRD_QUOTE_ACCEPTED = "BrdQuoteAccepted";
    public static final String SOURCES_ROUND_OPENED = "SourcesRoundOpened";
    /** GD-26: a technical design was requested (or retried); the worker enqueues TDD_GENERATION. */
    public static final String TDD_REQUESTED = "TddRequested";
    /** GD-33: a technical design was approved; the worker hands it to the project's Estimators. */
    public static final String TDD_APPROVED = "TddApproved";
    /** GD-34: a delivery plan version is to be written by the Planning agent. */
    public static final String PLAN_REQUESTED = "PlanRequested";
    /** GD-34: an estimate was approved; the worker hands it to the project's Estimators for planning. */
    public static final String ESTIMATE_APPROVED = "EstimateApproved";
    /** GD-34 phase 2: an Estimator asked to push the approved plan to GitHub Issues; the worker enqueues PLAN_PUSH. */
    public static final String PLAN_PUSH_REQUESTED = "PlanPushRequested";
    /** GD-36: a person paused, resumed, retried or skipped development work; the worker looks again at once. */
    public static final String DEVELOPMENT_CHANGED = "DevelopmentChanged";
    /** GD-33: an estimate version is to be written by the Estimation agent. */
    public static final String ESTIMATE_REQUESTED = "EstimateRequested";

    private static final Map<String, Integer> VERSIONS = Map.ofEntries(
            Map.entry(REQUIREMENT_SUBMITTED, 1),
            Map.entry(REPO_VALIDATION_STARTED, 1),
            Map.entry(MANUAL_INTAKE_ENTERED, 1),
            Map.entry(MANUAL_INTAKE_RESUMED, 1),
            Map.entry(REQUIREMENT_CANCELLED, 1),
            Map.entry(JOB_DEAD, 1),
            Map.entry(DOCUMENT_UPLOADED, 1),
            Map.entry(DOCUMENT_SCANNED, 1),
            Map.entry(DOCUMENT_FAILED, 1),
            Map.entry(DOCUMENT_PROCESSED, 1),
            Map.entry(REPO_VALIDATED, 1),
            Map.entry(DOCUMENT_DECISION_REQUESTED, 1),
            Map.entry(WORKFLOW_FAILED, 1),
            Map.entry(REPOSITORY_REGISTERED, 1),
            Map.entry(REPOSITORY_SNAPSHOT_INDEXED, 1),
            Map.entry(ANALYSIS_STARTED, 1),
            Map.entry(EXECUTION_STALE, 1),
            Map.entry(BRD_VERSION_CREATED, 1),
            Map.entry(REVIEW_QUEUED, 1),
            Map.entry(BRD_APPROVED_INTERNAL, 1),
            Map.entry(REVISION_REQUESTED, 1),
            Map.entry(REQUIREMENT_REJECTED, 1),
            Map.entry(BRD_RELEASED_TO_CLIENT, 1),
            Map.entry(CLIENT_CHANGES_REQUESTED, 1),
            Map.entry(APPROVAL_INVALIDATED, 1),
            Map.entry(CLARIFICATION_OPENED, 1),
            Map.entry(CLARIFICATION_ANSWERED, 1),
            Map.entry(DOCUMENT_DECISION_RECORDED, 1),
            Map.entry(RELEASE_APPROVED, 1),
            Map.entry(ADMIN_RETRY_REQUESTED, 1),
            Map.entry(ADMIN_RETRY_COMPLETED, 1),
            Map.entry(ADMIN_RETRY_REJECTED, 1),
            Map.entry(CHANGE_REQUEST_SUBMITTED, 1),
            Map.entry(CHANGE_REQUEST_ACCEPTED, 1),
            Map.entry(ENVIRONMENT_BRANCHES_CHANGED, 1),
            Map.entry(BRD_QUOTE_ACCEPTED, 1),
            Map.entry(SOURCES_ROUND_OPENED, 1),
            Map.entry(TDD_REQUESTED, 1),
            Map.entry(ESTIMATE_REQUESTED, 1),
            Map.entry(TDD_APPROVED, 1),
            Map.entry(PLAN_REQUESTED, 1),
            Map.entry(ESTIMATE_APPROVED, 1),
            Map.entry(PLAN_PUSH_REQUESTED, 1),
            Map.entry(DEVELOPMENT_CHANGED, 1));

    private EventTypes() {
    }

    public static int currentVersion(String eventType) {
        Integer version = VERSIONS.get(eventType);
        if (version == null) {
            throw new IllegalArgumentException("Unknown event type " + eventType);
        }
        return version;
    }

    /** Whether a consumer of the current version can read {@code version} (current or previous). */
    public static boolean isSupported(String eventType, int version) {
        int current = currentVersion(eventType);
        return version == current || version == current - 1 && version >= 1;
    }
}
