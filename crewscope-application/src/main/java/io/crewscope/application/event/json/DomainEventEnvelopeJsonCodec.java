package io.crewscope.application.event.json;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Canonical camelCase JSON codec for persisted domain event envelopes. */
public final class DomainEventEnvelopeJsonCodec {

    private final EventEnvelopeJsonSupport json;

    public DomainEventEnvelopeJsonCodec(ObjectMapper objectMapper) {
        this.json = new EventEnvelopeJsonSupport(objectMapper);
    }

    /** Encodes the business payload alone for the PostgreSQL JSONB fact column. */
    public String encodePayload(DomainEvent payload) {
        return json.write(json.payloadNode(Objects.requireNonNull(payload, "payload")));
    }

    /** Encodes every optional field explicitly as a value or JSON {@code null}. */
    public String encode(DomainEventEnvelope<? extends DomainEvent> envelope) {
        DomainEventEnvelope<? extends DomainEvent> event =
                Objects.requireNonNull(envelope, "envelope");
        ObjectNode root = json.objectNode();
        root.put("eventId", event.eventId().toString());
        root.put("eventType", event.eventType().value());
        root.put("schemaVersion", event.schemaVersion().toString());
        root.put("organizationId", event.organizationId().toString());
        json.putNullable(root, "teamId", event.teamId());
        json.putNullable(root, "workspaceId", event.workspaceId());
        root.put("aggregateType", event.aggregate().type());
        root.put("aggregateId", event.aggregate().id().toString());
        root.put("aggregateVersion", event.aggregateVersion());
        root.put("actorType", event.actor().type().name());
        json.putNullable(root, "actorId", event.actor().id());
        root.put("correlationId", event.correlationId().toString());
        json.putNullable(root, "causationId", event.causationId());
        json.putNullable(root, "idempotencyKey", event.idempotencyKey());
        root.put("occurredAt", event.occurredAt().toString());
        root.set("payload", json.payloadNode(event.payload()));
        return json.write(root);
    }

    /**
     * Decodes the current contract and older documents that omit fields declared optional.
     * Unknown envelope fields are ignored so additive schema changes remain readable.
     */
    public <T extends DomainEvent> DomainEventEnvelope<T> decode(String value, Class<T> payloadType) {
        ObjectNode root = json.readObject(value);
        try {
            EventActorType actorType =
                    json.requiredEnum(root, "actorType", EventActorType.class);
            Optional<PrincipalId> actorId =
                    json.optionalMappedText(root, "actorId", PrincipalId::from);
            return new DomainEventEnvelope<>(
                    json.requiredUuid(root, "eventId"),
                    EventType.from(json.requiredText(root, "eventType")),
                    SchemaVersion.from(json.requiredText(root, "schemaVersion")),
                    OrganizationId.from(json.requiredText(root, "organizationId")),
                    json.optionalMappedText(root, "teamId", TeamId::from),
                    json.optionalMappedText(root, "workspaceId", WorkspaceId::from),
                    new AggregateReference(
                            json.requiredText(root, "aggregateType"),
                            json.requiredUuid(root, "aggregateId")),
                    json.requiredLong(root, "aggregateVersion"),
                    new EventActor(actorType, actorId),
                    json.requiredUuid(root, "correlationId"),
                    json.optionalUuid(root, "causationId"),
                    json.optionalText(root, "idempotencyKey"),
                    UtcTimestamp.parse(json.requiredText(root, "occurredAt")),
                    json.readPayload(root, payloadType));
        } catch (EventEnvelopeJsonException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new EventEnvelopeJsonException("Event envelope violates the domain contract", exception);
        }
    }
}
