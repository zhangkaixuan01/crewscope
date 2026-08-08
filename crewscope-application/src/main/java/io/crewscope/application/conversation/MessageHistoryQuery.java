package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.Optional;

/** Scope-closed descending Message history query. */
public record MessageHistoryQuery(
        ConversationScope scope,
        ConversationId conversationId,
        Optional<ConversationMessageCursor> cursor,
        int limit) {
    public MessageHistoryQuery {
        scope = Objects.requireNonNull(scope, "scope");
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        cursor = Objects.requireNonNull(cursor, "cursor");
        ConversationId requiredConversationId = conversationId;
        cursor.ifPresent(value -> value.requireConversation(requiredConversationId));
        if (limit < 1 || limit > 100) {
            throw new DomainValidationException("messageHistoryQuery.limit", "must be between 1 and 100");
        }
    }
}
