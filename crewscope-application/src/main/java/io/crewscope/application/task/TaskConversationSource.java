package io.crewscope.application.task;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.MessageId;
import java.util.Objects;

/** Optional committed Conversation Message used as the reproducible Task trigger. */
public record TaskConversationSource(ConversationId conversationId, MessageId messageId) {

    public TaskConversationSource {
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        messageId = Objects.requireNonNull(messageId, "messageId");
    }
}
