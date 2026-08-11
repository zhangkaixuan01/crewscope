package io.crewscope.domain.conversation.event;

import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentStatus;
import java.util.Objects;
import java.util.UUID;

/** Version 1 fact emitted after the proposed Owner permanently rejects a TaskIntent. */
public record TaskIntentRejected(UUID conversationId, int proposalRevision, String reason)
    implements ConversationAssociatedEvent {

  public TaskIntentRejected {
    conversationId = Objects.requireNonNull(conversationId, "conversationId");
    if (proposalRevision < 1) {
      throw new IllegalArgumentException("proposalRevision must be positive");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    reason = reason.strip();
  }

  public static TaskIntentRejected from(TaskIntent intent) {
    TaskIntent source = Objects.requireNonNull(intent, "intent");
    if (source.status() != TaskIntentStatus.REJECTED) {
      throw new IllegalArgumentException("TaskIntent must be REJECTED");
    }
    return new TaskIntentRejected(
        source.conversationId().value(),
        source.proposalRevision(),
        source.decision().orElseThrow().reason().orElseThrow());
  }
}
