package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageSequence;
import java.util.Objects;

/** Conversation-bound keyset position for loading earlier Messages by descending sequence. */
public record ConversationMessageCursor(
        ConversationId conversationId, MessageSequence sequence) {

    public ConversationMessageCursor {
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        sequence = Objects.requireNonNull(sequence, "sequence");
    }

    /** Fails closed when a cursor is reused against another Conversation route. */
    public ConversationMessageCursor requireConversation(ConversationId expectedConversationId) {
        ConversationId requiredExpected =
                Objects.requireNonNull(expectedConversationId, "expectedConversationId");
        if (!conversationId.equals(requiredExpected)) {
            throw new IllegalArgumentException(
                    "message cursor must belong to the requested Conversation");
        }
        return this;
    }

    /** Returns whether the Message belongs before this cursor in descending history order. */
    public boolean isOlder(Message candidate) {
        Message requiredCandidate = Objects.requireNonNull(candidate, "candidate");
        requireConversation(requiredCandidate.conversationId());
        return requiredCandidate.sequence().compareTo(sequence) < 0;
    }
}
