package io.crewscope.application.task;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ConversationTaskLinkOrigin;
import java.util.Objects;
import java.util.Optional;

/** Safe Task summary and the immutable fact that associates it with the query source. */
public record TaskAssociationItem(
        TaskListItem task,
        Optional<ConversationTaskLinkOrigin> conversationOrigin,
        UtcTimestamp associatedAt) {

    public TaskAssociationItem {
        task = Objects.requireNonNull(task, "task");
        conversationOrigin = Objects.requireNonNull(
                conversationOrigin, "conversationOrigin");
        associatedAt = Objects.requireNonNull(associatedAt, "associatedAt");
    }
}
