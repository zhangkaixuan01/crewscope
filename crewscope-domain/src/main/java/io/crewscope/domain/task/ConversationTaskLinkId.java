package io.crewscope.domain.task;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.AggregateId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable identifier for a Conversation and Task relation. */
public record ConversationTaskLinkId(UUID value) implements AggregateId {

    private static final String ID_NAMESPACE = "io.crewscope/conversation-task-link/";

    public ConversationTaskLinkId {
        value = AggregateId.requireValue(value, "ConversationTaskLinkId");
    }

    public static ConversationTaskLinkId forPair(
            ConversationId conversationId, TaskId taskId) {
        String source = ID_NAMESPACE
                + Objects.requireNonNull(conversationId, "conversationId")
                + "/"
                + Objects.requireNonNull(taskId, "taskId");
        return new ConversationTaskLinkId(
                UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)));
    }

    public static ConversationTaskLinkId from(String value) {
        return new ConversationTaskLinkId(
                AggregateId.parseCanonical(value, "ConversationTaskLinkId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
