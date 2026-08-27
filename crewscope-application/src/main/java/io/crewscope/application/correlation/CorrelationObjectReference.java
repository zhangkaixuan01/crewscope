package io.crewscope.application.correlation;

import java.util.Objects;
import java.util.UUID;

/** Safe typed object identity; external Provider identities are deliberately excluded. */
public record CorrelationObjectReference(CorrelationObjectType type, UUID id) {

    public CorrelationObjectReference {
        type = Objects.requireNonNull(type, "type");
        id = Objects.requireNonNull(id, "id");
    }
}
