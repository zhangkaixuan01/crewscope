package io.crewscope.application.event.json;

/** Reports an event envelope that cannot be encoded or decoded against the canonical contract. */
public final class EventEnvelopeJsonException extends RuntimeException {

    public EventEnvelopeJsonException(String message) {
        super(message);
    }

    public EventEnvelopeJsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
