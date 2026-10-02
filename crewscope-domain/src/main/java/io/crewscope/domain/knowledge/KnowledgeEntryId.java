package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed Team Knowledge entry identifier. */
public record KnowledgeEntryId(UUID value) implements AggregateId {

    public KnowledgeEntryId {
        value = AggregateId.requireValue(value, "KnowledgeEntryId");
    }

    public static KnowledgeEntryId generate() {
        return new KnowledgeEntryId(AggregateId.generateValue());
    }

    public static KnowledgeEntryId from(String value) {
        return new KnowledgeEntryId(AggregateId.parseCanonical(value, "KnowledgeEntryId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
