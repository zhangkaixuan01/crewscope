package io.crewscope.application.workdesk;

/** Signals that a WorkDesk section cursor is older than the configured cursor maximum age. */
public final class WorkDeskCursorExpiredException extends RuntimeException {

    public WorkDeskCursorExpiredException() {
        super("The WorkDesk section cursor has expired");
    }
}
