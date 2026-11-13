package io.github.tony079.sdlc.project.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A project (product or entity) of the organisation (PRD section 4A; Garuda GD-02).
 *
 * @param myRole the caller's role in the project, or null when they are not a member (an Organisation Admin sees
 *               every project of the organisation without being a member, GD-06)
 */
public record ProjectView(UUID id, String name, String description, String status, ProjectRole myRole,
                          int memberCount, OffsetDateTime createdAt, OffsetDateTime archivedAt) {
}
