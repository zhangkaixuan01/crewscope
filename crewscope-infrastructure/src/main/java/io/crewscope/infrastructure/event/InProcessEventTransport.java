package io.crewscope.infrastructure.event;

import io.crewscope.application.event.publication.DomainEventConsumer;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.application.event.publication.EventTransport;
import java.util.List;
import java.util.Objects;

/** M0 local transport that dispatches each event through per-consumer idempotency receipts. */
public class InProcessEventTransport implements EventTransport {

    private final List<DomainEventConsumer> consumers;
    private final IdempotentEventDispatcher dispatcher;

    public InProcessEventTransport(
            List<DomainEventConsumer> consumers, IdempotentEventDispatcher dispatcher) {
        this.consumers = List.copyOf(Objects.requireNonNull(consumers, "consumers"));
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public void publish(EventPublication publication) {
        EventPublication event = Objects.requireNonNull(publication, "publication");
        if (consumers.isEmpty()) {
            throw new IllegalStateException("No DomainEventConsumer is configured");
        }
        for (DomainEventConsumer consumer : consumers) {
            dispatcher.dispatch(consumer, event);
        }
    }
}
