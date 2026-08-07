package io.crewscope.infrastructure.event.projection;

/** Signals malformed or inconsistent data at the durable event transport boundary. */
public class InvalidProjectionEventException extends RuntimeException {

    public InvalidProjectionEventException(String message) {
        super(message);
    }

    public InvalidProjectionEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
