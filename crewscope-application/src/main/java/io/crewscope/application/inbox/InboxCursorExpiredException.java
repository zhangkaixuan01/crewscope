package io.crewscope.application.inbox;

/** Signals that a page cursor belongs to a projection generation that is no longer active. */
public final class InboxCursorExpiredException extends RuntimeException {

    public InboxCursorExpiredException() {
        super("The Inbox cursor projection generation is no longer active");
    }
}
