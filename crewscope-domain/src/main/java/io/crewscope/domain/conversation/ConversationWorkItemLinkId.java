package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.workitem.WorkItemId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable identifier for a Conversation and WorkItem relation. */
public record ConversationWorkItemLinkId(UUID value) implements AggregateId {

    private static final String ID_NAMESPACE = "io.crewscope/conversation-work-item-link/";

    public ConversationWorkItemLinkId {
        value = AggregateId.requireValue(value, "ConversationWorkItemLinkId");
    }

    /** Produces one durable relation identity for a Conversation and WorkItem pair. */
    public static ConversationWorkItemLinkId forPair(
            ConversationId conversationId, WorkItemId workItemId) {
        String source = ID_NAMESPACE
                + Objects.requireNonNull(conversationId, "conversationId")
                + "/"
                + Objects.requireNonNull(workItemId, "workItemId");
        return new ConversationWorkItemLinkId(
                UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)));
    }

    public static ConversationWorkItemLinkId from(String value) {
        return new ConversationWorkItemLinkId(
                AggregateId.parseCanonical(value, "ConversationWorkItemLinkId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
