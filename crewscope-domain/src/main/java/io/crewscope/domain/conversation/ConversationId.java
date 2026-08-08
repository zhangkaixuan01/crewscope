package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed Conversation aggregate identifier. */
public record ConversationId(UUID value) implements AggregateId {

    public ConversationId {
        value = AggregateId.requireValue(value, "ConversationId");
    }

    public static ConversationId generate() {
        return new ConversationId(AggregateId.generateValue());
    }

    public static ConversationId from(String value) {
        return new ConversationId(AggregateId.parseCanonical(value, "ConversationId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
