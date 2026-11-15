package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;

import org.springframework.stereotype.Component;

/**
 * Re-evaluates guard 9.4 whenever an input of the analysis may have become ready: repository validated, snapshot
 * indexed, a document processed or failed. Runs in the dispatcher's tenant transaction (effectively once per event).
 */
@Component
@org.springframework.context.annotation.Profile("!migrate & !provision")
class AnalysisCoordinator implements EventConsumer {

    private static final Set<String> TRIGGERS = Set.of(EventTypes.REPO_VALIDATED, EventTypes.REPOSITORY_SNAPSHOT_INDEXED,
            EventTypes.DOCUMENT_PROCESSED, EventTypes.DOCUMENT_FAILED, EventTypes.BRD_QUOTE_ACCEPTED);

    private final AnalysisStarter starter;
    private final InputChangeConsumers inputChanges;
    private final ReviewConsumers reviews;

    AnalysisCoordinator(AnalysisStarter starter, InputChangeConsumers inputChanges, ReviewConsumers reviews) {
        this.starter = starter;
        this.inputChanges = inputChanges;
        this.reviews = reviews;
    }

    @Override
    public String name() {
        return "analysis-guard";
    }

    @Override
    public boolean accepts(String eventType) {
        return TRIGGERS.contains(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        UUID requirementId = event.uuid("requirementId");
        if (starter.tryStart(requirementId).isEmpty()) {
            inputChanges.resumeIfUnblocked(requirementId);
            if (EventTypes.REPOSITORY_SNAPSHOT_INDEXED.equals(event.eventType())) {
                reviews.reanalyseIfWaiting(requirementId); // GD-23: re-analysis waits for the new round's snapshots
            }
        }
    }
}
