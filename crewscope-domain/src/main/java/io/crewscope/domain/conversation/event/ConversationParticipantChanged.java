package io.crewscope.domain.conversation.event;

import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationParticipantRole;
import io.crewscope.domain.conversation.ConversationParticipantStatus;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;

/** Version 1 participant lifecycle fact used with joined, reactivated and left event types. */
public record ConversationParticipantChanged(
    UUID conversationId,
    UUID participantId,
    UUID principalId,
    Optional<UUID> teamMemberId,
    ConversationParticipantRole role,
    ConversationParticipantStatus status)
    implements ConversationAssociatedEvent {

  public ConversationParticipantChanged {
    conversationId = Objects.requireNonNull(conversationId, "conversationId");
    participantId = Objects.requireNonNull(participantId, "participantId");
    principalId = Objects.requireNonNull(principalId, "principalId");
    teamMemberId = Objects.requireNonNull(teamMemberId, "teamMemberId");
    role = Objects.requireNonNull(role, "role");
    status = Objects.requireNonNull(status, "status");
  }

  public static ConversationParticipantChanged from(ConversationParticipant participant) {
    ConversationParticipant source = Objects.requireNonNull(participant, "participant");
    return new ConversationParticipantChanged(
        source.conversationId().value(),
        source.id().value(),
        source.principalId().value(),
        source.teamMemberId().map(value -> value.value()),
        source.role(),
        source.status());
  }
}
