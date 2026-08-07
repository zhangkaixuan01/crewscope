package io.crewscope.application.event.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DomainEventEnvelopeJsonCodecTest {

    private static final DomainEventEnvelopeJsonCodec CODEC =
            new DomainEventEnvelopeJsonCodec(new ObjectMapper());

    @Test
    void writesTheCanonicalEnvelopeWithStableNamesOrderAndRepresentations() {
        String json = CODEC.encode(domainEvent());

        assertEquals(
                "{\"eventId\":\"01989ee2-f6b0-7cda-97c4-1b337043d301\","
                        + "\"eventType\":\"WORK_ITEM_STATUS_CHANGED\",\"schemaVersion\":\"1\","
                        + "\"organizationId\":\"01989ee2-f6b0-7cda-97c4-1b337043d310\","
                        + "\"teamId\":\"01989ee2-f6b0-7cda-97c4-1b337043d311\","
                        + "\"workspaceId\":\"01989ee2-f6b0-7cda-97c4-1b337043d312\","
                        + "\"aggregateType\":\"WORK_ITEM\","
                        + "\"aggregateId\":\"01989ee2-f6b0-7cda-97c4-1b337043d313\","
                        + "\"aggregateVersion\":3,\"actorType\":\"USER\","
                        + "\"actorId\":\"01989ee2-f6b0-7cda-97c4-1b337043d314\","
                        + "\"correlationId\":\"01989ee2-f6b0-7cda-97c4-1b337043d303\","
                        + "\"causationId\":\"01989ee2-f6b0-7cda-97c4-1b337043d304\","
                        + "\"idempotencyKey\":\"command-42\","
                        + "\"occurredAt\":\"2026-08-06T10:00:00.123456Z\","
                        + "\"payload\":{\"previousStatus\":\"READY\","
                        + "\"currentStatus\":\"IN_PROGRESS\"}}",
                json);
    }

    @Test
    void roundTripsEveryStronglyTypedField() {
        DomainEventEnvelope<StatusChangedPayload> source = domainEvent();

        DomainEventEnvelope<StatusChangedPayload> decoded =
                CODEC.decode(CODEC.encode(source), StatusChangedPayload.class);

        assertEquals(source, decoded);
    }

    @Test
    void readsOlderDocumentsWithoutOptionalFieldsAndIgnoresFutureEnvelopeFields() {
        String legacyJson = "{"
                + "\"eventId\":\"01989ee2-f6b0-7cda-97c4-1b337043d301\","
                + "\"eventType\":\"WORK_ITEM_CREATED\",\"schemaVersion\":\"1\","
                + "\"organizationId\":\"01989ee2-f6b0-7cda-97c4-1b337043d310\","
                + "\"aggregateType\":\"WORK_ITEM\","
                + "\"aggregateId\":\"01989ee2-f6b0-7cda-97c4-1b337043d313\","
                + "\"aggregateVersion\":0,\"actorType\":\"SERVICE\","
                + "\"correlationId\":\"01989ee2-f6b0-7cda-97c4-1b337043d303\","
                + "\"occurredAt\":\"2026-08-06T10:00:00Z\","
                + "\"futureEnvelopeField\":{\"enabled\":true},"
                + "\"payload\":{\"previousStatus\":\"NONE\",\"currentStatus\":\"BACKLOG\"}}";

        DomainEventEnvelope<StatusChangedPayload> decoded =
                CODEC.decode(legacyJson, StatusChangedPayload.class);

        assertTrue(decoded.teamId().isEmpty());
        assertTrue(decoded.workspaceId().isEmpty());
        assertTrue(decoded.actor().id().isEmpty());
        assertTrue(decoded.causationId().isEmpty());
        assertTrue(decoded.idempotencyKey().isEmpty());
    }

    @Test
    void rejectsMalformedIdentifiersVersionsAndPayloadShapes() {
        String canonical = CODEC.encode(domainEvent());

        assertThrows(
                EventEnvelopeJsonException.class,
                () -> CODEC.decode(
                        canonical.replace(
                                "01989ee2-f6b0-7cda-97c4-1b337043d301", "not-a-uuid"),
                        StatusChangedPayload.class));
        assertThrows(
                EventEnvelopeJsonException.class,
                () -> CODEC.decode(
                        canonical.replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":\"01\""),
                        StatusChangedPayload.class));
        assertThrows(
                EventEnvelopeJsonException.class,
                () -> CODEC.decode(
                        canonical.replace("\"aggregateVersion\":3", "\"aggregateVersion\":-1"),
                        StatusChangedPayload.class));
        assertThrows(
                EventEnvelopeJsonException.class,
                () -> CODEC.decode(
                        canonical.replace(
                                "{\"previousStatus\":\"READY\",\"currentStatus\":\"IN_PROGRESS\"}",
                                "[]"),
                        StatusChangedPayload.class));
    }

    private static DomainEventEnvelope<StatusChangedPayload> domainEvent() {
        return new DomainEventEnvelope<>(
                UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d301"),
                EventType.from("WORK_ITEM_STATUS_CHANGED"),
                SchemaVersion.V1,
                OrganizationId.from("01989ee2-f6b0-7cda-97c4-1b337043d310"),
                Optional.of(TeamId.from("01989ee2-f6b0-7cda-97c4-1b337043d311")),
                Optional.of(WorkspaceId.from("01989ee2-f6b0-7cda-97c4-1b337043d312")),
                new AggregateReference(
                        "WORK_ITEM", UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d313")),
                3,
                EventActor.principal(
                        EventActorType.USER,
                        PrincipalId.from("01989ee2-f6b0-7cda-97c4-1b337043d314")),
                UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d303"),
                Optional.of(UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d304")),
                Optional.of("command-42"),
                UtcTimestamp.parse("2026-08-06T10:00:00.123456Z"),
                new StatusChangedPayload("READY", "IN_PROGRESS"));
    }

    public record StatusChangedPayload(String previousStatus, String currentStatus)
            implements DomainEvent {}
}
