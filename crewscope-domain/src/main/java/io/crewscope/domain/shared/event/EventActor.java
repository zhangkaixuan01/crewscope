package io.crewscope.domain.shared.event;

import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.Optional;

/** Effective actor recorded on an immutable domain fact. */
public record EventActor(EventActorType type, Optional<PrincipalId> id) {

    public EventActor {
        type = Objects.requireNonNull(type, "type");
        id = Objects.requireNonNull(id, "id");
        if (type != EventActorType.SERVICE && id.isEmpty()) {
            throw new IllegalArgumentException("Only a SERVICE event actor may omit its PrincipalId");
        }
    }

    /** Creates an actor backed by a persisted Principal. */
    public static EventActor principal(EventActorType type, PrincipalId id) {
        return new EventActor(type, Optional.of(Objects.requireNonNull(id, "id")));
    }

    /** Creates a bootstrap or infrastructure service actor without a persisted Principal. */
    public static EventActor anonymousService() {
        return new EventActor(EventActorType.SERVICE, Optional.empty());
    }
}
