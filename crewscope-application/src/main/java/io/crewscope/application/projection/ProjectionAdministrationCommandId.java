package io.crewscope.application.projection;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Idempotency identity of one projection administration command. */
public record ProjectionAdministrationCommandId(UUID value) implements AggregateId {

    public ProjectionAdministrationCommandId {
        value = AggregateId.requireValue(value, "ProjectionAdministrationCommandId");
    }

    public static ProjectionAdministrationCommandId generate() {
        return new ProjectionAdministrationCommandId(AggregateId.generateValue());
    }
}
