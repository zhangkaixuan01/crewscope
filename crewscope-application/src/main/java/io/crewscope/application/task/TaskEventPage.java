package io.crewscope.application.task;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ascending page from one durable Task Event stream. */
public record TaskEventPage(List<TaskEvent> events, boolean hasMore, boolean taskTerminal) {

    public TaskEventPage {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (events.isEmpty() && hasMore) {
            throw new IllegalArgumentException("an empty event page cannot have more rows");
        }
    }

    public Optional<TaskEventCursor> nextCursor() {
        return events.isEmpty()
                ? Optional.empty()
                : Optional.of(events.get(events.size() - 1).cursor());
    }

    /** A terminal Task closes SSE only after every retained event has been delivered. */
    public boolean streamComplete() {
        return taskTerminal && !hasMore;
    }
}
