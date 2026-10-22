package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.Conversation;
import java.util.Objects;

/**
 * One list row seen through the requesting viewer's eyes: the Conversation plus its unread
 * message count (M13b-A07 contract: {@code max(0, lastMessageSequence - lastReadSequence)},
 * Agent viewers never accrue unread, and a Conversation without messages reads as zero).
 */
public record ConversationSummary(Conversation conversation, long unreadCount) {

  public ConversationSummary {
    conversation = Objects.requireNonNull(conversation, "conversation");
    if (unreadCount < 0) {
      throw new IllegalArgumentException("unreadCount must not be negative");
    }
  }
}
