package io.github.tony079.sdlc.workflow.internal;

import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.PermanentEventFailure;
import io.github.tony079.sdlc.workflow.api.Route;

import org.springframework.stereotype.Component;

/**
 * Routes a submitted requirement (TDD T02/T03) when RequirementSubmitted is delivered. The engine performs the
 * transition in the dispatcher's transaction, together with this consumer's processed_event row.
 */
@Component
class RoutingConsumer implements EventConsumer {

    private final JdbcWorkflowEngine engine;

    RoutingConsumer(JdbcWorkflowEngine engine) {
        this.engine = engine;
    }

    @Override
    public String name() {
        return "workflow-routing";
    }

    @Override
    public boolean accepts(String eventType) {
        return EventTypes.REQUIREMENT_SUBMITTED.equals(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        Route route;
        try {
            route = Route.valueOf(event.text("route"));
        } catch (IllegalArgumentException e) {
            throw new PermanentEventFailure("PAYLOAD_INVALID");
        }
        engine.route(event.uuid("requirementId"), route);
    }
}
