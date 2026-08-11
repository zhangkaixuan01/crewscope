package io.crewscope.domain.conversation.event;

import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.MessageType;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Version 1 fact emitted after one immutable Conversation Message is committed. */
public record ConversationMessagePosted(
    UUID messageId,
    long sequence,
    MessageType messageType,
    UUID participantId,
    UUID authorPrincipalId,
    String contentMarkdown)
    implements DomainEvent {

  public ConversationMessagePosted {
    messageId = Objects.requireNonNull(messageId, "messageId");
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    messageType = Objects.requireNonNull(messageType, "messageType");
    if (messageType == MessageType.SYSTEM_NOTICE) {
      throw new IllegalArgumentException("messageType must identify an authored message");
    }
    participantId = Objects.requireNonNull(participantId, "participantId");
    authorPrincipalId = Objects.requireNonNull(authorPrincipalId, "authorPrincipalId");
    contentMarkdown = new MessageContent(contentMarkdown).markdown();
  }

  /** Creates the authored-message event payload without exposing persistence-only metadata. */
  public static ConversationMessagePosted from(Message message) {
    Message source = Objects.requireNonNull(message, "message");
    return new ConversationMessagePosted(
        source.id().value(),
        source.sequence().value(),
        source.type(),
        source.participantId().orElseThrow().value(),
        source.authorPrincipalId().orElseThrow().value(),
        source.content().markdown());
  }
}
