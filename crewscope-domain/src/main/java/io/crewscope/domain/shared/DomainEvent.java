package io.crewscope.domain.shared;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.UUID;

public interface DomainEvent {

    UUID eventId();

    String eventType();

    UtcTimestamp occurredAt();
}
