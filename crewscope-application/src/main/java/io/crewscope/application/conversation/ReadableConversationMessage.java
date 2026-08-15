package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.Message;
import java.util.Objects;

/** Exact Conversation and Message pair proven readable by the current Team member. */
public record ReadableConversationMessage(Conversation conversation, Message message) {

    public ReadableConversationMessage {
        conversation = Objects.requireNonNull(conversation, "conversation");
        message = Objects.requireNonNull(message, "message");
        if (!message.scope().equals(conversation.scope())
                || !message.conversationId().equals(conversation.id())) {
            throw new IllegalArgumentException("message must belong to the readable Conversation");
        }
    }
}
