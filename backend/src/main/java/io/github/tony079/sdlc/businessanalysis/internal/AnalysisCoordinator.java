package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.Set;

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
            EventTypes.DOCUMENT_PROCESSED, EventTypes.DOCUMENT_FAILED);

    private final AnalysisStarter starter;

    AnalysisCoordinator(AnalysisStarter starter) {
        this.starter = starter;
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
        starter.tryStart(event.uuid("requirementId"));
    }
}
