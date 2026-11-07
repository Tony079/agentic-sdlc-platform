package io.github.tony079.sdlc.requirement.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The current revision of a requirement as analysis input (TDD 12.3). For the trusted engine and BA agent in the worker
 * only; never exposed through an API. Requires an active transaction in the requirement's tenant.
 */
public interface RequirementInputs {

    record RevisionInput(UUID requirementId, UUID projectId, UUID createdBy, int revisionNo, String contentHash,
                         RequirementView.Content content) {
    }

    Optional<RevisionInput> currentRevision(UUID requirementId);
}
