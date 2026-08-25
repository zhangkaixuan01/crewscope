package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identifier of one confirmed Lark member mapping. */
public record LarkMemberMappingId(UUID value) implements AggregateId {
    public LarkMemberMappingId {
        value = AggregateId.requireValue(value, "LarkMemberMappingId");
    }

    public static LarkMemberMappingId generate() {
        return new LarkMemberMappingId(AggregateId.generateValue());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
