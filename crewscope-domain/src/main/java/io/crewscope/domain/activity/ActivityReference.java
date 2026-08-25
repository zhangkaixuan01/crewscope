package io.crewscope.domain.activity;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.UUID;

/** Safe typed reference used for Activity filtering and authoritative resource navigation. */
public record ActivityReference(ActivityReferenceType type, UUID id) {

    public ActivityReference {
        type = Objects.requireNonNull(type, "type");
        id = AggregateId.requireValue(id, "ActivityReference.id");
    }
}
