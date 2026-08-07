package io.crewscope.application.event;

/** Persistence Port for durable event publication requests. */
public interface OutboxRepository {

    /** Enqueues one pending publication inside the caller's existing business transaction. */
    void enqueue(PendingOutboxEvent event);
}
