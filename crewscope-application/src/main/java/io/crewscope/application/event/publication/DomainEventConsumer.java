package io.crewscope.application.event.publication;

/** Named consumer whose database side effects are protected by an event receipt. */
public interface DomainEventConsumer {

    /** Stable name included in the idempotency key and retained across deployments. */
    String consumerName();

    /** Handles one canonical event inside the dispatcher's local database transaction. */
    void consume(EventPublication publication);
}
