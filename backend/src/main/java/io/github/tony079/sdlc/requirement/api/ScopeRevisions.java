package io.github.tony079.sdlc.requirement.api;

import java.util.UUID;

import io.github.tony079.sdlc.requirement.api.RequirementView.Content;

/**
 * Applies an accepted change request's scope to a requirement (owner decision C-09): a new immutable revision with the
 * accepted content, authored by the client user who submitted the change. Only the change-request workflow calls this,
 * after the Client Approver's acceptance; the requirement's kind and repository cannot change this way.
 */
public interface ScopeRevisions {

    /** The current revision's content and number (any requirement of the current tenant). */
    Current current(UUID requirementId);

    /** Creates the next revision; returns its number. Fails if the revision moved since {@code expectedRevisionNo}. */
    int applyAcceptedScope(UUID requirementId, int expectedRevisionNo, Content content, UUID submittedBy);

    /** SHA-256 of the canonical content (same as revisions). */
    String hash(Content content);

    record Current(int revisionNo, Content content, String contentHash) {
    }
}
