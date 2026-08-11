package io.crewscope.application.conversation;

import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Identifies one current Team USER to add or reactivate in a Conversation. */
public record AddConversationParticipantCommand(PrincipalId userPrincipalId) {

  public AddConversationParticipantCommand {
    userPrincipalId = Objects.requireNonNull(userPrincipalId, "userPrincipalId");
  }
}
