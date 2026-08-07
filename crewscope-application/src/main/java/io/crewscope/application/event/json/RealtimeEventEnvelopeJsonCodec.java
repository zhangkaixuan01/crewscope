package io.crewscope.application.event.json;

import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.event.StreamType;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Canonical camelCase JSON codec shared by AG-UI, Conversation and Team streams. */
public final class RealtimeEventEnvelopeJsonCodec {

    private final EventEnvelopeJsonSupport json;

    public RealtimeEventEnvelopeJsonCodec(ObjectMapper objectMapper) {
        this.json = new EventEnvelopeJsonSupport(objectMapper);
    }

    /** Encodes every optional field explicitly as a value or JSON {@code null}. */
    public String encode(RealtimeEventEnvelope<?> envelope) {
        RealtimeEventEnvelope<?> event = Objects.requireNonNull(envelope, "envelope");
        ObjectNode root = json.objectNode();
        root.put("eventId", event.eventId().toString());
        json.putNullable(root, "domainEventId", event.domainEventId());
        root.put("streamType", event.streamType().name());
        root.put("eventType", event.eventType().value());
        root.put("schemaVersion", event.schemaVersion().toString());
        if (event.aggregate().isPresent()) {
            AggregateReference aggregate = event.aggregate().orElseThrow();
            root.put("aggregateType", aggregate.type());
            root.put("aggregateId", aggregate.id().toString());
            root.put("aggregateVersion", event.aggregateVersion().orElseThrow());
        } else {
            root.set("aggregateType", json.nullNode());
            root.set("aggregateId", json.nullNode());
            root.set("aggregateVersion", json.nullNode());
        }
        root.put("correlationId", event.correlationId().toString());
        json.putNullable(root, "causationId", event.causationId());
        root.put("occurredAt", event.occurredAt().toString());
        root.set("payload", json.payloadNode(event.payload()));
        return json.write(root);
    }

    /** Decodes additive contracts while ignoring unknown envelope fields. */
    public <T> RealtimeEventEnvelope<T> decode(String value, Class<T> payloadType) {
        ObjectNode root = json.readObject(value);
        try {
            Optional<String> aggregateType = json.optionalText(root, "aggregateType");
            Optional<UUID> aggregateId = json.optionalUuid(root, "aggregateId");
            if (aggregateType.isPresent() != aggregateId.isPresent()) {
                throw new EventEnvelopeJsonException(
                        "aggregateType and aggregateId must either both be present or both be null");
            }
            Optional<AggregateReference> aggregate = aggregateType.map(
                    type -> new AggregateReference(type, aggregateId.orElseThrow()));
            return new RealtimeEventEnvelope<>(
                    json.requiredUuid(root, "eventId"),
                    json.optionalUuid(root, "domainEventId"),
                    json.requiredEnum(root, "streamType", StreamType.class),
                    EventType.from(json.requiredText(root, "eventType")),
                    SchemaVersion.from(json.requiredText(root, "schemaVersion")),
                    aggregate,
                    json.optionalLong(root, "aggregateVersion"),
                    json.requiredUuid(root, "correlationId"),
                    json.optionalUuid(root, "causationId"),
                    UtcTimestamp.parse(json.requiredText(root, "occurredAt")),
                    json.readPayload(root, payloadType));
        } catch (EventEnvelopeJsonException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EventEnvelopeJsonException("Realtime event violates the domain contract", exception);
        }
    }
}
