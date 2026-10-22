package io.crewscope.domain.conversation.event;

import io.crewscope.domain.conversation.ConversationParticipant;
import java.util.Objects;
import java.util.UUID;

/**
 * Version 1 read-cursor fact emitted when a participant's receipt advances, so badge
 * projections can converge without re-reading the participant row (M13b-A07). Replayed
 * receipts that leave the cursor unchanged are no-ops and emit nothing.
 */
public record ConversationReadReceiptReceived(
    UUID conversationId, UUID participantId, UUID principalId, long lastReadSequence)
    implements ConversationAssociatedEvent {

  public ConversationReadReceiptReceived {
    conversationId = Objects.requireNonNull(conversationId, "conversationId");
    participantId = Objects.requireNonNull(participantId, "participantId");
    principalId = Objects.requireNonNull(principalId, "principalId");
    if (lastReadSequence <= 0) {
      throw new IllegalArgumentException("lastReadSequence must be positive");
    }
  }

  public static ConversationReadReceiptReceived from(ConversationParticipant participant) {
    ConversationParticipant source = Objects.requireNonNull(participant, "participant");
    return new ConversationReadReceiptReceived(
        source.conversationId().value(),
        source.id().value(),
        source.principalId().value(),
        source.lastReadSequence());
  }
}
