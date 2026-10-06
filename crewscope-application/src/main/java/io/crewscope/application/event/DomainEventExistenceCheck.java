package io.crewscope.application.event;

import java.util.UUID;

/** Read-side existence probe used to keep deterministic replay idempotent (M10-F03). */
public interface DomainEventExistenceCheck {

    /** True when the canonical event log already holds the given event id. */
    boolean exists(UUID eventId);
}
