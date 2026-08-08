package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.Conversation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One deterministic Conversation page and its continuation position. */
public record ConversationPage(
        List<Conversation> conversations, Optional<ConversationListCursor> nextCursor) {
    public ConversationPage {
        conversations = List.copyOf(Objects.requireNonNull(conversations, "conversations"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
