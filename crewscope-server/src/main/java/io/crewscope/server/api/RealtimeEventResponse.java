package io.crewscope.server.api;

import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import java.util.Objects;
import java.util.UUID;

/** Canonical flat HTTP representation shared by all CrewScope realtime streams. */
public record RealtimeEventResponse<T>(
    UUID eventId,
    UUID domainEventId,
    String streamType,
    String eventType,
    String schemaVersion,
    String aggregateType,
    UUID aggregateId,
    Long aggregateVersion,
    UUID correlationId,
    UUID causationId,
    String occurredAt,
    T payload) {

  public static <T> RealtimeEventResponse<T> from(RealtimeEventEnvelope<T> envelope) {
    RealtimeEventEnvelope<T> source = Objects.requireNonNull(envelope, "envelope");
    AggregateReference aggregate = source.aggregate().orElse(null);
    return new RealtimeEventResponse<>(
        source.eventId(),
        source.domainEventId().orElse(null),
        source.streamType().name(),
        source.eventType().value(),
        source.schemaVersion().toString(),
        aggregate == null ? null : aggregate.type(),
        aggregate == null ? null : aggregate.id(),
        source.aggregateVersion().orElse(null),
        source.correlationId(),
        source.causationId().orElse(null),
        source.occurredAt().toString(),
        source.payload());
  }
}
