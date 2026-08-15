package io.crewscope.application.task;

import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import java.util.Map;
import java.util.Objects;

/** One sanitized durable Task event with its stream position and relationship context. */
public record TaskEvent(
        TaskEventCursor cursor,
        TaskEventContext context,
        boolean projectionGap,
        RealtimeEventEnvelope<Map<String, Object>> envelope) {

    public TaskEvent {
        cursor = Objects.requireNonNull(cursor, "cursor");
        context = Objects.requireNonNull(context, "context");
        envelope = Objects.requireNonNull(envelope, "envelope");
        if (!cursor.taskId().equals(context.taskId())) {
            throw new IllegalArgumentException("cursor and context Task IDs must match");
        }
        if (!cursor.eventId().equals(envelope.eventId())) {
            throw new IllegalArgumentException("cursor and envelope event IDs must match");
        }
    }
}
