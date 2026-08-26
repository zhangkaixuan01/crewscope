package io.crewscope.application.activity;

import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.domain.activity.ActivityEventId;
import io.crewscope.domain.activity.TeamSequence;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded Team Activity snapshot and its high-water cursor from one database read snapshot.
 *
 * <p>The high-water cursor may be after the last filtered row. This closes the race between
 * installing the snapshot and starting realtime gap delivery, including an empty filtered result.
 */
public record TeamActivitySnapshot(
        TeamActivitySnapshotRequest request,
        ActivityCursorScope cursorScope,
        List<ActivityEvent> events,
        Optional<TeamActivityCursor> snapshotCursor,
        boolean hasMore) {

    public TeamActivitySnapshot {
        request = Objects.requireNonNull(request, "request");
        cursorScope = Objects.requireNonNull(cursorScope, "cursorScope");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        snapshotCursor = Objects.requireNonNull(snapshotCursor, "snapshotCursor");
        requireScope(request, cursorScope);
        if (events.size() > request.limit()) {
            throw new IllegalArgumentException("Team Activity snapshot exceeds the requested limit");
        }
        if (!events.isEmpty() && snapshotCursor.isEmpty()) {
            throw new IllegalArgumentException(
                    "A non-empty Team Activity snapshot requires a high-water cursor");
        }
        if (hasMore && (events.isEmpty() || snapshotCursor.isEmpty())) {
            throw new IllegalArgumentException(
                    "A truncated Team Activity snapshot requires rows and a high-water cursor");
        }
        if (snapshotCursor.isPresent()) {
            snapshotCursor.orElseThrow().requireScope(cursorScope);
        }
        validateEvents(request, cursorScope, events, snapshotCursor);
    }

    private static void requireScope(
            TeamActivitySnapshotRequest request, ActivityCursorScope scope) {
        if (!request.organizationId().equals(scope.organizationId())
                || !request.teamId().equals(scope.teamId())
                || !request.projectionName().equals(scope.projectionName())
                || !request.filter().fingerprint().equals(scope.filterFingerprint())) {
            throw new IllegalArgumentException(
                    "Team Activity snapshot scope does not match the request");
        }
    }

    private static void validateEvents(
            TeamActivitySnapshotRequest request,
            ActivityCursorScope scope,
            List<ActivityEvent> events,
            Optional<TeamActivityCursor> snapshotCursor) {
        TeamSequence previous = null;
        Set<ActivityEventId> eventIds = new HashSet<>();
        for (ActivityEvent event : events) {
            TeamActivityCursor eventCursor = TeamActivityCursor.from(scope, event);
            if (!request.filter().matches(event)) {
                throw new IllegalArgumentException(
                        "Team Activity snapshot contains an event outside the normalized filter");
            }
            if (previous != null && !event.teamSequence().isAfter(previous)) {
                throw new IllegalArgumentException(
                        "Team Activity snapshot TeamSequence values must be strictly increasing");
            }
            if (!eventIds.add(event.id())) {
                throw new IllegalArgumentException(
                        "Team Activity snapshot must not repeat an event identity");
            }
            if (snapshotCursor.isPresent()
                    && eventCursor.teamSequence().isAfter(
                            snapshotCursor.orElseThrow().teamSequence())) {
                throw new IllegalArgumentException(
                        "Team Activity snapshot row is after its high-water cursor");
            }
            previous = event.teamSequence();
        }
    }
}
