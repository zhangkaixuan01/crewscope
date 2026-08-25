package io.crewscope.domain.activity;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.UUID;

/** Primary public business object affected by an Activity. */
public record ActivitySubject(ActivitySubjectType type, UUID id) {

    public ActivitySubject {
        type = Objects.requireNonNull(type, "type");
        id = AggregateId.requireValue(id, "ActivitySubject.id");
    }
}
