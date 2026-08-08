package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Conversation snapshot and immutable Message produced by one atomic append operation. */
public record ConversationMessageAppend(Conversation conversation, Message message) {

    public ConversationMessageAppend {
        conversation = Objects.requireNonNull(conversation, "conversation");
        message = Objects.requireNonNull(message, "message");
        if (!message.conversationId().equals(conversation.id())
                || !message.scope().equals(conversation.scope())
                || conversation.lastMessageSequence().filter(message.sequence()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "conversationMessageAppend.message",
                    "must be the latest Message of the returned Conversation snapshot");
        }
    }
}
