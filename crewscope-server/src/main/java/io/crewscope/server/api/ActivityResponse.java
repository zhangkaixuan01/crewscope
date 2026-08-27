package io.crewscope.server.api;

import io.crewscope.domain.activity.ActivityEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit allow-list DTO for public Team and WorkItem Activity responses. */
public record ActivityResponse(
    UUID eventId,
    UUID domainEventId,
    long teamSequence,
    String eventType,
    String category,
    String visibility,
    ActivitySubjectResponse subject,
    ActivityActorResponse actor,
    List<ActivityReferenceResponse> references,
    Instant occurredAt,
    ActivityPayloadResponse payload) {

  public static ActivityResponse from(ActivityEvent event) {
    return new ActivityResponse(
        event.id().value(),
        event.domainEventId(),
        event.teamSequence().value(),
        event.eventType().value(),
        event.category().name(),
        event.visibility().name(),
        new ActivitySubjectResponse(event.subject().type().name(), event.subject().id()),
        new ActivityActorResponse(
            event.actor().type().name(),
            event.actor().principalId().map(value -> value.value()).orElse(null)),
        event.references().stream()
            .map(value -> new ActivityReferenceResponse(value.type().name(), value.id()))
            .toList(),
        event.occurredAt().value(),
        new ActivityPayloadResponse(
            event.payload().schema().name(),
            event.payload().schema().version().value(),
            event.payload().values()));
  }

  public record ActivitySubjectResponse(String type, UUID id) {}

  public record ActivityActorResponse(String type, UUID principalId) {}

  public record ActivityReferenceResponse(String type, UUID id) {}

  public record ActivityPayloadResponse(
      String schemaName, int schemaVersion, Map<String, String> values) {}
}
