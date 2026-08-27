package io.crewscope.application.correlation;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Payload-free event summary returned by the Correlation application boundary. */
public record CorrelationEvent(
        UUID eventId,
        CorrelationEventSource source,
        String eventType,
        String actorType,
        Optional<UUID> actorId,
        Optional<String> outcome,
        UtcTimestamp occurredAt,
        List<CorrelationObjectReference> references) {

    public CorrelationEvent {
        eventId = Objects.requireNonNull(eventId, "eventId");
        source = Objects.requireNonNull(source, "source");
        eventType = requireCode(eventType, "eventType");
        actorType = requireCode(actorType, "actorType");
        actorId = Objects.requireNonNull(actorId, "actorId");
        outcome = Objects.requireNonNull(outcome, "outcome").map(value ->
                requireCode(value, "outcome"));
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        if (references.size() != references.stream().distinct().count()) {
            throw new IllegalArgumentException("Correlation event references must be unique");
        }
    }

    private static String requireCode(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > 200
                || !normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(field + " must be a bounded uppercase code");
        }
        return normalized;
    }
}
