package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.Conversation;
import java.util.List;
import java.util.Objects;

/** Transactionally consistent Conversation and identity-enriched participant snapshot. */
public record ConversationDetails(
    Conversation conversation, List<ConversationParticipantView> participants,
    long viewerUnreadCount) {

  public ConversationDetails {
    conversation = Objects.requireNonNull(conversation, "conversation");
    participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
    if (viewerUnreadCount < 0) {
      throw new IllegalArgumentException("viewerUnreadCount must not be negative");
    }
    Conversation requiredConversation = conversation;
    if (participants.stream()
        .anyMatch(
            view ->
                !view.participant().conversationId().equals(requiredConversation.id())
                    || !view.participant().scope().equals(requiredConversation.scope()))) {
      throw new IllegalArgumentException("participants must belong to the Conversation");
    }
  }
}
