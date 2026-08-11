package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** User-controlled fields accepted when starting a Personal Agent Conversation. */
public record CreateConversationCommand(String title, ConversationVisibility visibility) {

  public CreateConversationCommand {
    if (title == null || title.isBlank()) {
      throw new DomainValidationException("conversation.title", "must not be blank");
    }
    title = title.strip();
    if (title.length() > Conversation.MAX_TITLE_LENGTH) {
      throw new DomainValidationException(
          "conversation.title",
          "must contain at most " + Conversation.MAX_TITLE_LENGTH + " characters");
    }
    visibility = Objects.requireNonNull(visibility, "visibility");
  }
}
