package io.crewscope.application.conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ascending page from one durable Conversation Event stream. */
public record ConversationEventPage(List<ConversationEvent> events, boolean hasMore) {

  public ConversationEventPage {
    events = List.copyOf(Objects.requireNonNull(events, "events"));
    if (events.isEmpty() && hasMore) {
      throw new IllegalArgumentException("an empty event page cannot have more rows");
    }
  }

  /** Cursor of the final event actually returned to the caller. */
  public Optional<ConversationEventCursor> nextCursor() {
    return events.isEmpty()
        ? Optional.empty()
        : Optional.of(events.get(events.size() - 1).cursor());
  }
}
