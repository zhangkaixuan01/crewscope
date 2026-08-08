package io.crewscope.application.workitem;

import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Canonical event exposed by the M1 WorkItem timeline query. */
public record WorkItemTimelineEvent(
    UUID eventId,
    Optional<UUID> domainEventId,
    UUID canonicalEventId,
    WorkItemTimelineSource source,
    String eventType,
    String schemaVersion,
    String aggregateType,
    UUID aggregateId,
    Optional<Long> aggregateVersion,
    EventActorType actorType,
    Optional<PrincipalId> actorPrincipalId,
    Optional<String> actorDisplayName,
    UUID correlationId,
    Optional<UUID> causationId,
    UtcTimestamp occurredAt,
    String outcome,
    String payloadJson) {

  public WorkItemTimelineEvent {
    eventId = AggregateId.requireValue(eventId, "WorkItemTimelineEvent.eventId");
    domainEventId = requireOptionalUuid(domainEventId, "domainEventId");
    canonicalEventId =
        AggregateId.requireValue(canonicalEventId, "WorkItemTimelineEvent.canonicalEventId");
    source = Objects.requireNonNull(source, "source");
    eventType = requireText(eventType, "eventType");
    schemaVersion = requireText(schemaVersion, "schemaVersion");
    aggregateType = requireText(aggregateType, "aggregateType");
    aggregateId = AggregateId.requireValue(aggregateId, "WorkItemTimelineEvent.aggregateId");
    aggregateVersion = Objects.requireNonNull(aggregateVersion, "aggregateVersion");
    aggregateVersion.ifPresent(
        version -> {
          if (version < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
          }
        });
    actorType = Objects.requireNonNull(actorType, "actorType");
    actorPrincipalId = Objects.requireNonNull(actorPrincipalId, "actorPrincipalId");
    actorDisplayName =
        Objects.requireNonNull(actorDisplayName, "actorDisplayName")
            .map(name -> requireText(name, "actorDisplayName"));
    if (actorPrincipalId.isEmpty() && actorDisplayName.isPresent()) {
      throw new IllegalArgumentException("actorDisplayName requires actorPrincipalId");
    }
    correlationId =
        AggregateId.requireValue(correlationId, "WorkItemTimelineEvent.correlationId");
    causationId = requireOptionalUuid(causationId, "causationId");
    occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    outcome = requireText(outcome, "outcome");
    payloadJson = requireText(payloadJson, "payloadJson");
  }

  /** Returns the keyset position immediately after this event. */
  public WorkItemTimelineCursor cursor() {
    return new WorkItemTimelineCursor(occurredAt, canonicalEventId);
  }

  private static Optional<UUID> requireOptionalUuid(Optional<UUID> value, String name) {
    return Objects.requireNonNull(value, name)
        .map(candidate -> AggregateId.requireValue(candidate, "WorkItemTimelineEvent." + name));
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.strip();
  }
}
