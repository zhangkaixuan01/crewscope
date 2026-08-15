package io.crewscope.application.task;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.ConversationStatus;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ConversationTaskLinkOrigin;
import java.util.Objects;

/** Current visible Conversation summary linked to one Task. */
public record TaskConversationAssociation(
        ConversationId id,
        ConversationScope scope,
        String title,
        ConversationVisibility visibility,
        ConversationStatus status,
        ConversationTaskLinkOrigin origin,
        UtcTimestamp associatedAt) {

    public TaskConversationAssociation {
        id = Objects.requireNonNull(id, "id");
        scope = Objects.requireNonNull(scope, "scope");
        title = Objects.requireNonNull(title, "title");
        visibility = Objects.requireNonNull(visibility, "visibility");
        status = Objects.requireNonNull(status, "status");
        origin = Objects.requireNonNull(origin, "origin");
        associatedAt = Objects.requireNonNull(associatedAt, "associatedAt");
    }
}
