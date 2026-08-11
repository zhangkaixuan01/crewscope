package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationParticipant;
import java.util.List;
import java.util.Objects;

/** Transactionally consistent Conversation and participant lifecycle snapshot. */
public record ConversationDetails(
    Conversation conversation, List<ConversationParticipant> participants) {

  public ConversationDetails {
    conversation = Objects.requireNonNull(conversation, "conversation");
    participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
    Conversation requiredConversation = conversation;
    if (participants.stream()
        .anyMatch(
            participant ->
                !participant.conversationId().equals(requiredConversation.id())
                    || !participant.scope().equals(requiredConversation.scope()))) {
      throw new IllegalArgumentException("participants must belong to the Conversation");
    }
  }
}
