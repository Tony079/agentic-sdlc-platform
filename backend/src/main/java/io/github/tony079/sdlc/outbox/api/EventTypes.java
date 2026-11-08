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

    private static final Map<String, Integer> VERSIONS = Map.ofEntries(
            Map.entry(REQUIREMENT_SUBMITTED, 1),
            Map.entry(REPO_VALIDATION_STARTED, 1),
            Map.entry(MANUAL_INTAKE_ENTERED, 1),
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
            Map.entry(DOCUMENT_DECISION_RECORDED, 1));

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
