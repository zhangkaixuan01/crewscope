package io.crewscope.application.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.DomainEventEnvelope;

/** Append-only persistence Port for immutable domain facts. */
public interface DomainEventStore {

    /** Appends one event inside the caller's existing business transaction. */
    void append(DomainEventEnvelope<? extends DomainEvent> event);
}
