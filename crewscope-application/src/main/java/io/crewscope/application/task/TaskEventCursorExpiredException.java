package io.crewscope.application.task;

/** Indicates that a once-valid durable Task Event position is no longer retained. */
public final class TaskEventCursorExpiredException extends RuntimeException {

    public TaskEventCursorExpiredException() {
        super("Task Event cursor is no longer retained");
    }
}
