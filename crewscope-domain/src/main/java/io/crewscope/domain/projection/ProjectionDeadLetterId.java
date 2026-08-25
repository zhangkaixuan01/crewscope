package io.crewscope.domain.projection;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of a generation-scoped projection Dead Letter. */
public record ProjectionDeadLetterId(UUID value) implements AggregateId {

    public ProjectionDeadLetterId {
        value = AggregateId.requireValue(value, "ProjectionDeadLetterId");
    }

    public static ProjectionDeadLetterId generate() {
        return new ProjectionDeadLetterId(AggregateId.generateValue());
    }
}
