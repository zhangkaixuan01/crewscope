package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationParticipantRole;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import java.util.Objects;
import java.util.Optional;

/** Participant lifecycle fact enriched with its authoritative Principal and optional owner. */
public record ConversationParticipantView(
    ConversationParticipant participant, Principal principal, Optional<Principal> owner) {

  public ConversationParticipantView {
    participant = Objects.requireNonNull(participant, "participant");
    principal = Objects.requireNonNull(principal, "principal");
    owner = Objects.requireNonNull(owner, "owner");
    if (!participant.principalId().equals(principal.id())
        || !participant
            .scope()
            .organizationId()
            .equals(principal.scope().organizationId())) {
      throw new IllegalArgumentException("principal must identify the Conversation participant");
    }
    boolean agentRole = participant.role() == ConversationParticipantRole.AGENT;
    if ((agentRole && !principal.type().isAgent())
        || (!agentRole && principal.type() != PrincipalType.USER)) {
      throw new IllegalArgumentException(
          "participant role must match the Principal behavior-subject type");
    }
    if (!ownerMatches(principal, owner)) {
      throw new IllegalArgumentException("owner must match the participant Principal ownership");
    }
  }

  private static boolean ownerMatches(Principal principal, Optional<Principal> owner) {
    if (principal.ownerPrincipalId().isEmpty()) {
      return owner.isEmpty();
    }
    return owner
        .filter(value -> value.id().equals(principal.ownerPrincipalId().orElseThrow()))
        .filter(value -> value.type() == PrincipalType.USER)
        .filter(
            value ->
                value
                    .scope()
                    .organizationId()
                    .equals(principal.scope().organizationId()))
        .isPresent();
  }
}
