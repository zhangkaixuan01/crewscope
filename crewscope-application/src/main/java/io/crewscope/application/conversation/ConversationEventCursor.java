package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.UUID;

/** Scope-bound durable position in one Conversation Event stream. */
public record ConversationEventCursor(
    OrganizationId organizationId,
    TeamId teamId,
    ConversationId conversationId,
    long position,
    UUID eventId) {

  public ConversationEventCursor {
    organizationId = Objects.requireNonNull(organizationId, "organizationId");
    teamId = Objects.requireNonNull(teamId, "teamId");
    conversationId = Objects.requireNonNull(conversationId, "conversationId");
    if (position < 1) {
      throw new IllegalArgumentException("position must be positive");
    }
    eventId = Objects.requireNonNull(eventId, "eventId");
  }

  /** Fails closed when a Cursor is reused on another scoped route. */
  public ConversationEventCursor requireStream(
      OrganizationId expectedOrganizationId,
      TeamId expectedTeamId,
      ConversationId expectedConversationId) {
    if (!organizationId.equals(Objects.requireNonNull(expectedOrganizationId, "expectedOrganizationId"))
        || !teamId.equals(Objects.requireNonNull(expectedTeamId, "expectedTeamId"))
        || !conversationId.equals(
            Objects.requireNonNull(expectedConversationId, "expectedConversationId"))) {
      throw new IllegalArgumentException("cursor must belong to the requested Conversation stream");
    }
    return this;
  }
}
