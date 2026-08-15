package io.crewscope.application.task;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Current visible Conversation slice associated with one Task. */
public record TaskConversationAssociationPage(
        List<TaskConversationAssociation> items,
        Optional<TaskAssociationCursor> nextCursor) {

    public TaskConversationAssociationPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
