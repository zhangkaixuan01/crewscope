package io.crewscope.domain.inbox;

/** Non-negative canonical source version used to separate newly actionable facts. */
public record InboxSourceRevision(long value) implements Comparable<InboxSourceRevision> {

    public static final InboxSourceRevision INITIAL = new InboxSourceRevision(0);

    public InboxSourceRevision {
        if (value < 0) {
            throw new IllegalArgumentException("InboxSourceRevision must not be negative");
        }
    }

    public InboxSourceRevision next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("InboxSourceRevision is exhausted");
        }
        return new InboxSourceRevision(value + 1);
    }

    @Override
    public int compareTo(InboxSourceRevision other) {
        return Long.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }
}
