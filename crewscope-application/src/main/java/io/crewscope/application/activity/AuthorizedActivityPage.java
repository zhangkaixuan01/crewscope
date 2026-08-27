package io.crewscope.application.activity;

import io.crewscope.domain.activity.ActivityEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Publicly visible Activity rows plus the opaque position of the scanned durable page. */
public record AuthorizedActivityPage(
        List<ActivityEvent> events,
        boolean hasMore,
        Optional<TeamActivityCursor> nextCursor) {

    public AuthorizedActivityPage {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (hasMore != nextCursor.isPresent()) {
            throw new IllegalArgumentException(
                    "Activity page continuation must match its hasMore state");
        }
    }
}
