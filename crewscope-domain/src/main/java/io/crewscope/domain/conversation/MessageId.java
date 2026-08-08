package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed immutable Message identifier. */
public record MessageId(UUID value) implements AggregateId {

    public MessageId {
        value = AggregateId.requireValue(value, "MessageId");
    }

    public static MessageId generate() {
        return new MessageId(AggregateId.generateValue());
    }

    public static MessageId from(String value) {
        return new MessageId(AggregateId.parseCanonical(value, "MessageId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
