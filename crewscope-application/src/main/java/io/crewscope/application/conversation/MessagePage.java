package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.Message;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One descending Message history page and its Conversation-bound continuation. */
public record MessagePage(List<Message> messages, Optional<ConversationMessageCursor> nextCursor) {
    public MessagePage {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
