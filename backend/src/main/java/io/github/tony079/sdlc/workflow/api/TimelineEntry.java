package io.github.tony079.sdlc.workflow.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One state change in a requirement's history. Content-free: no reason text, only identifiers and states. */
public record TimelineEntry(int seq, WorkflowState fromState, WorkflowState toState, String transition,
                            UUID actorId, OffsetDateTime occurredAt) {
}
