package io.crewscope.application.workitem;

/** Signals that a WorkItem list cursor is older than the configured cursor maximum age. */
public final class WorkItemCursorExpiredException extends RuntimeException {

    public WorkItemCursorExpiredException() {
        super("The WorkItem list cursor has expired");
    }
}
