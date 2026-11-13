package io.github.tony079.sdlc.requirement.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.workflow.api.WorkflowState;

/** API representations of requirements (PRD FR-REQ-01/03/04). */
public final class RequirementView {

    private RequirementView() {
    }

    /** The eight PRD FR-REQ-01 form fields, with the existing-repository / new-product choice (FR-REQ-06). */
    public record Content(String kind, String title, String description, String businessObjective,
                          String expectedOutcomes, String acceptanceCriteria, String repositoryUrl,
                          String constraints, String integrations) {
    }

    public record Summary(UUID id, String key, String title, WorkflowState state, UUID projectId,
                          int currentRevisionNo, OffsetDateTime updatedAt) {
    }

    public record SummaryPage(List<Summary> items, String nextCursor) {
    }

    public record RevisionSummary(int revisionNo, String contentHash, OffsetDateTime createdAt) {
    }

    public record Revision(UUID requirementId, int revisionNo, Content content, String contentHash,
                           UUID createdBy, OffsetDateTime createdAt) {
    }

    /**
     * @param restricted GD-05: visible only to the owner and the project's Approvers
     * @param ownedByMe  the caller created it (only the owner edits, submits and cancels it)
     * @param myRole     the caller's role in the requirement's project
     */
    public record Detail(UUID id, String key, UUID projectId, WorkflowState state, long version,
                         int currentRevisionNo, Content content, List<RevisionSummary> revisions,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt, boolean restricted, boolean ownedByMe,
                         io.github.tony079.sdlc.project.api.ProjectRole myRole) {
    }
}
