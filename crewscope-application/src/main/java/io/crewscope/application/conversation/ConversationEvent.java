package io.crewscope.application.conversation;

import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import java.util.Map;
import java.util.Objects;

/** One durable Conversation stream event together with its resumable position. */
public record ConversationEvent(
    ConversationEventCursor cursor,
    RealtimeEventEnvelope<Map<String, Object>> envelope) {

  public ConversationEvent {
    cursor = Objects.requireNonNull(cursor, "cursor");
    envelope = Objects.requireNonNull(envelope, "envelope");
    if (!cursor.eventId().equals(envelope.eventId())) {
      throw new IllegalArgumentException("cursor and envelope event IDs must match");
    }
  }
}
