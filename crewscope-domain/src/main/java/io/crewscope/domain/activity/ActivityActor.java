package io.crewscope.domain.activity;

import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.Optional;

/** Public identity of the actor that caused the canonical business fact. */
public record ActivityActor(EventActorType type, Optional<PrincipalId> principalId) {

    public ActivityActor {
        type = Objects.requireNonNull(type, "type");
        principalId = Objects.requireNonNull(principalId, "principalId");
        if (type != EventActorType.SERVICE && principalId.isEmpty()) {
            throw new IllegalArgumentException("Only a SERVICE Activity actor may omit PrincipalId");
        }
    }

    public static ActivityActor from(EventActor actor) {
        EventActor required = Objects.requireNonNull(actor, "actor");
        return new ActivityActor(required.type(), required.id());
    }
}
