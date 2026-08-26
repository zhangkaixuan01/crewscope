package io.crewscope.infrastructure.event.projection;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded history page; the next cursor is persisted by the future M6-I02 Supervisor. */
public record ProjectionHistoryPage(
        List<ProjectionHistoryEvent> events, Optional<ProjectionHistoryCursor> nextCursor) {

    public ProjectionHistoryPage {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (events.isEmpty() != nextCursor.isEmpty()) {
            throw new IllegalArgumentException("History cursor must identify the last returned event");
        }
        if (!events.isEmpty()
                && !events.get(events.size() - 1).cursor().equals(nextCursor.orElseThrow())) {
            throw new IllegalArgumentException("History cursor must equal the last event position");
        }
    }

    public static ProjectionHistoryPage empty() {
        return new ProjectionHistoryPage(List.of(), Optional.empty());
    }
}
