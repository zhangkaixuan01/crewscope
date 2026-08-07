package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Verifies transport coordinates before a published event can reach a projection transaction. */
class ProjectionEventJsonMapperTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-07T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProjectionEventJsonMapper mapper = new ProjectionEventJsonMapper(objectMapper);

    @Test
    void acceptsOnlyTheCanonicalDomainEventTopic() {
        EventPublication canonical = publication(PendingOutboxEvent.DOMAIN_EVENTS_TOPIC);
        EventPublication wrongTopic = new EventPublication(
                canonical.outboxId(),
                canonical.eventId(),
                "crewscope.realtime-events.v1",
                canonical.partitionKey(),
                canonical.deliveryAttempt(),
                canonical.occurredAt(),
                canonical.eventJson());

        assertEquals(canonical.eventId(), mapper.map(canonical).eventId());
        assertThrows(InvalidProjectionEventException.class, () -> mapper.map(wrongTopic));
    }

    private EventPublication publication(String topic) {
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String partitionKey = "%s:WORK_ITEM:%s".formatted(organizationId, aggregateId);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("eventType", "WORK_ITEM_CREATED");
        root.put("schemaVersion", "1");
        root.put("organizationId", organizationId.toString());
        root.set("teamId", objectMapper.nullNode());
        root.set("workspaceId", objectMapper.nullNode());
        root.put("aggregateType", "WORK_ITEM");
        root.put("aggregateId", aggregateId.toString());
        root.put("aggregateVersion", 0);
        root.put("actorType", "USER");
        root.put("actorId", actorId.toString());
        root.put("correlationId", correlationId.toString());
        root.set("causationId", objectMapper.nullNode());
        root.set("idempotencyKey", objectMapper.nullNode());
        root.put("occurredAt", OCCURRED_AT.toString());
        root.set("payload", objectMapper.createObjectNode().put("title", "Review baseline"));

        return new EventPublication(
                UUID.randomUUID(),
                eventId,
                topic,
                partitionKey,
                1,
                UtcTimestamp.from(OCCURRED_AT),
                objectMapper.writeValueAsString(root));
    }
}
