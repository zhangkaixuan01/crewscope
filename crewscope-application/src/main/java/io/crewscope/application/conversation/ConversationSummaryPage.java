package io.crewscope.application.conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The viewer-facing page: visible Conversations with their unread counts and the cursor. */
public record ConversationSummaryPage(
        List<ConversationSummary> conversations, Optional<ConversationListCursor> nextCursor) {
    public ConversationSummaryPage {
        conversations = List.copyOf(Objects.requireNonNull(conversations, "conversations"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
