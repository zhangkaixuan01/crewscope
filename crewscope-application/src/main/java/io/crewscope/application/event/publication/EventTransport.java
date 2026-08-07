package io.crewscope.application.event.publication;

/** Outbound Port that delivers a persisted event to its configured transport. */
@FunctionalInterface
public interface EventTransport {

    /** Publishes one message; failures must be reported by throwing a runtime exception. */
    void publish(EventPublication publication);
}
