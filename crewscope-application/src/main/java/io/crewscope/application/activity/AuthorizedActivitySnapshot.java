package io.crewscope.application.activity;

import io.crewscope.domain.activity.ActivityEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Authorization-filtered snapshot retaining both history continuation and realtime high-water. */
public record AuthorizedActivitySnapshot(
        List<ActivityEvent> events,
        boolean hasMore,
        Optional<TeamActivityCursor> nextCursor,
        Optional<TeamActivityCursor> snapshotCursor) {

    public AuthorizedActivitySnapshot {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        snapshotCursor = Objects.requireNonNull(snapshotCursor, "snapshotCursor");
        if (hasMore != nextCursor.isPresent()) {
            throw new IllegalArgumentException(
                    "Activity snapshot continuation must match its hasMore state");
        }
    }
}
