package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.MessageContent;
import java.util.Objects;

/** Trusted user-message input after Markdown normalization and transport-safety validation. */
public record PostConversationMessageCommand(MessageContent content) {

  public PostConversationMessageCommand {
    content = Objects.requireNonNull(content, "content");
  }

  public static PostConversationMessageCommand fromMarkdown(String markdown) {
    return new PostConversationMessageCommand(new MessageContent(markdown));
  }
}
