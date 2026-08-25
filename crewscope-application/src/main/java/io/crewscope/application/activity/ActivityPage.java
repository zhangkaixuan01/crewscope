package io.crewscope.application.activity;

import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.domain.activity.ActivityEventId;
import io.crewscope.domain.activity.TeamSequence;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Validated ascending slice shared by Team and WorkItem Activity query surfaces. */
public record ActivityPage(
        ActivityQuery query, List<ActivityEvent> events, boolean hasMore) {

    public ActivityPage {
        query = Objects.requireNonNull(query, "query");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (events.size() > query.limit()) {
            throw new IllegalArgumentException("Activity page exceeds the requested limit");
        }
        if (hasMore && events.isEmpty()) {
            throw new IllegalArgumentException("Activity page with hasMore must contain a cursor row");
        }
        validateEvents(query, events);
    }

    public Optional<TeamActivityCursor> nextCursor() {
        return hasMore && !events.isEmpty()
                ? Optional.of(TeamActivityCursor.from(
                        query.cursorScope(), events.get(events.size() - 1)))
                : Optional.empty();
    }

    /** Cursor safe for SSE resume even when the bounded snapshot has no following history page. */
    public Optional<TeamActivityCursor> resumeCursor() {
        if (!events.isEmpty()) {
            return Optional.of(TeamActivityCursor.from(
                    query.cursorScope(), events.get(events.size() - 1)));
        }
        return query.after();
    }

    private static void validateEvents(ActivityQuery query, List<ActivityEvent> events) {
        ActivityCursorScope scope = query.cursorScope();
        TeamSequence previous = query.after().map(TeamActivityCursor::teamSequence).orElse(null);
        Set<ActivityEventId> ids = new HashSet<>();
        for (ActivityEvent event : events) {
            TeamActivityCursor.from(scope, event);
            if (!query.filter().matches(event)) {
                throw new IllegalArgumentException(
                        "Activity page contains an event outside the normalized filter");
            }
            if (previous != null && !event.teamSequence().isAfter(previous)) {
                throw new IllegalArgumentException(
                        "Activity page TeamSequence values must be strictly increasing");
            }
            if (!ids.add(event.id())) {
                throw new IllegalArgumentException("Activity page must not repeat an event identity");
            }
            previous = event.teamSequence();
        }
    }
}
