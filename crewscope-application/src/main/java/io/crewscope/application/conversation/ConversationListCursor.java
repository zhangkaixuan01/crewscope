package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Stable keyset cursor ordered by activity time and Conversation ID in descending order. */
public record ConversationListCursor(UtcTimestamp updatedAt, ConversationId id) {

    public ConversationListCursor {
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        id = Objects.requireNonNull(id, "id");
    }

    public static ConversationListCursor from(Conversation conversation) {
        Conversation requiredConversation = Objects.requireNonNull(conversation, "conversation");
        return new ConversationListCursor(
                requiredConversation.audit().updatedAt(), requiredConversation.id());
    }

    /** Returns whether the candidate belongs after this cursor in the descending list. */
    public boolean isOlder(Conversation candidate) {
        return ConversationListOrder.isOlder(candidate, this);
    }
}
