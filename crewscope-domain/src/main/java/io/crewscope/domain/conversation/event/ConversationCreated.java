package io.crewscope.domain.conversation.event;

import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Version 1 fact emitted for a complete Personal Agent Conversation foundation. */
public record ConversationCreated(
    String title,
    ConversationVisibility visibility,
    UUID ownerMemberId,
    UUID ownerPrincipalId,
    UUID personalAgentPrincipalId,
    UUID ownerParticipantId,
    UUID agentParticipantId)
    implements DomainEvent {

  public ConversationCreated {
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("title must not be blank");
    }
    title = title.strip();
    visibility = Objects.requireNonNull(visibility, "visibility");
    ownerMemberId = Objects.requireNonNull(ownerMemberId, "ownerMemberId");
    ownerPrincipalId = Objects.requireNonNull(ownerPrincipalId, "ownerPrincipalId");
    personalAgentPrincipalId =
        Objects.requireNonNull(personalAgentPrincipalId, "personalAgentPrincipalId");
    ownerParticipantId = Objects.requireNonNull(ownerParticipantId, "ownerParticipantId");
    agentParticipantId = Objects.requireNonNull(agentParticipantId, "agentParticipantId");
  }

  public static ConversationCreated from(PersonalConversationInitialization initialization) {
    PersonalConversationInitialization source =
        Objects.requireNonNull(initialization, "initialization");
    return new ConversationCreated(
        source.conversation().title(),
        source.conversation().visibility(),
        source.conversation().ownerMemberId().value(),
        source.conversation().ownerPrincipalId().value(),
        source.conversation().personalAgentPrincipalId().value(),
        source.ownerParticipant().id().value(),
        source.agentParticipant().id().value());
  }
}
