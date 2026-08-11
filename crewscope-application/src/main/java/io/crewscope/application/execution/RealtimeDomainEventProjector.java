package io.crewscope.application.execution;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.StreamType;
import java.util.Objects;
import java.util.UUID;

/** Projects committed business facts while preserving their stable DomainEvent correlation. */
public final class RealtimeDomainEventProjector {

    /** Projects with the stable stream-specific ID used by durable realtime feeds. */
    public <T extends DomainEvent> RealtimeEventEnvelope<T> project(
            StreamType streamType,
            DomainEventEnvelope<T> domainEvent) {
        DomainEventEnvelope<T> source = Objects.requireNonNull(domainEvent, "domainEvent");
        return project(
                RealtimeStreamEventIds.forDomain(streamType, source.eventId()),
                streamType,
                source);
    }

    public <T extends DomainEvent> RealtimeEventEnvelope<T> project(
            UUID realtimeEventId,
            StreamType streamType,
            DomainEventEnvelope<T> domainEvent) {
        StreamType requiredStream = Objects.requireNonNull(streamType, "streamType");
        if (requiredStream == StreamType.AG_UI) {
            throw new IllegalArgumentException(
                    "persisted DomainEvents must use a durable Conversation or Team stream");
        }
        return RealtimeEventEnvelope.fromDomain(
                Objects.requireNonNull(realtimeEventId, "realtimeEventId"),
                requiredStream,
                Objects.requireNonNull(domainEvent, "domainEvent"));
    }
}
