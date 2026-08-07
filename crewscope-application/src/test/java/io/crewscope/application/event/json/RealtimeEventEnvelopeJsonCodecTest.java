package io.crewscope.application.event.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RealtimeEventEnvelopeJsonCodecTest {

    private static final RealtimeEventEnvelopeJsonCodec CODEC =
            new RealtimeEventEnvelopeJsonCodec(new ObjectMapper());

    @Test
    void roundTripsATransientAgUiEventWithExplicitNullCoordinates() {
        RealtimeEventEnvelope<ProgressPayload> source = RealtimeEventEnvelope.transientAgUi(
                UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d321"),
                EventType.from("TOOL_PROGRESS"),
                SchemaVersion.V1,
                UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d322"),
                Optional.empty(),
                UtcTimestamp.parse("2026-08-06T10:00:00Z"),
                new ProgressPayload(50));

        String json = CODEC.encode(source);
        RealtimeEventEnvelope<ProgressPayload> decoded =
                CODEC.decode(json, ProgressPayload.class);

        assertEquals(source, decoded);
        assertEquals(
                "{\"eventId\":\"01989ee2-f6b0-7cda-97c4-1b337043d321\","
                        + "\"domainEventId\":null,\"streamType\":\"AG_UI\","
                        + "\"eventType\":\"TOOL_PROGRESS\",\"schemaVersion\":\"1\","
                        + "\"aggregateType\":null,\"aggregateId\":null,"
                        + "\"aggregateVersion\":null,"
                        + "\"correlationId\":\"01989ee2-f6b0-7cda-97c4-1b337043d322\","
                        + "\"causationId\":null,\"occurredAt\":\"2026-08-06T10:00:00Z\","
                        + "\"payload\":{\"percent\":50}}",
                json);
    }

    @Test
    void rejectsPartialAggregateCoordinatesAndNonObjectPayloads() {
        String partialAggregate = "{"
                + "\"eventId\":\"01989ee2-f6b0-7cda-97c4-1b337043d321\","
                + "\"domainEventId\":null,\"streamType\":\"TEAM\","
                + "\"eventType\":\"WORK_ITEM_CHANGED\",\"schemaVersion\":\"1\","
                + "\"aggregateType\":\"WORK_ITEM\",\"aggregateId\":null,"
                + "\"aggregateVersion\":null,"
                + "\"correlationId\":\"01989ee2-f6b0-7cda-97c4-1b337043d322\","
                + "\"causationId\":null,\"occurredAt\":\"2026-08-06T10:00:00Z\","
                + "\"payload\":{\"percent\":50}}";

        assertThrows(
                EventEnvelopeJsonException.class,
                () -> CODEC.decode(partialAggregate, ProgressPayload.class));
        assertThrows(
                EventEnvelopeJsonException.class,
                () -> CODEC.decode(
                        partialAggregate
                                .replace("\"aggregateType\":\"WORK_ITEM\"", "\"aggregateType\":null")
                                .replace("{\"percent\":50}", "\"progress\""),
                        ProgressPayload.class));
    }

    public record ProgressPayload(int percent) {}
}
