package io.crewscope.application.correlation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One bounded event page plus its bidirectional object graph. */
public record CorrelationPage(
        UUID correlationId,
        List<CorrelationEvent> events,
        List<CorrelationObjectLink> objects,
        boolean hasMore,
        Optional<CorrelationCursor> nextCursor) {

    public CorrelationPage {
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (hasMore != nextCursor.isPresent()) {
            throw new IllegalArgumentException("nextCursor must be present exactly when more rows exist");
        }
    }

    /** Reverse edge from one object to every event in the current page that references it. */
    public record CorrelationObjectLink(
            CorrelationObjectReference object, List<UUID> eventIds) {

        public CorrelationObjectLink {
            object = Objects.requireNonNull(object, "object");
            eventIds = List.copyOf(Objects.requireNonNull(eventIds, "eventIds"));
            if (eventIds.isEmpty() || eventIds.size() != eventIds.stream().distinct().count()) {
                throw new IllegalArgumentException("Object links require unique related events");
            }
        }
    }
}
