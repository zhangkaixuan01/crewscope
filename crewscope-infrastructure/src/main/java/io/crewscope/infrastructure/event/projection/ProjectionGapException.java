package io.crewscope.infrastructure.event.projection;

/** Signals that a partition received a future aggregate version before its predecessor. */
public class ProjectionGapException extends RuntimeException {

    public ProjectionGapException(String message) {
        super(message);
    }
}
