package io.github.tony079.sdlc.project.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A tenant-owned project (PRD section 4A). */
public record ProjectView(UUID id, String name, OffsetDateTime createdAt) {
}
