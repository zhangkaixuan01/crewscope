package io.crewscope.application.inbox;

/** Open, non-archived count and its unread subset for one Inbox category. */
public record InboxTypeCount(long total, long unread) {

    public InboxTypeCount {
        if (total < 0 || unread < 0 || unread > total) {
            throw new IllegalArgumentException("Inbox counts must satisfy 0 <= unread <= total");
        }
    }
}
