package io.crewscope.domain.projection;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one projection rebuild attempt. */
public record ProjectionRebuildJobId(UUID value) implements AggregateId {

    public ProjectionRebuildJobId {
        value = AggregateId.requireValue(value, "ProjectionRebuildJobId");
    }

    public static ProjectionRebuildJobId generate() {
        return new ProjectionRebuildJobId(AggregateId.generateValue());
    }
}
