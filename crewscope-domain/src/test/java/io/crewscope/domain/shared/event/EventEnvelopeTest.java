package io.crewscope.domain.shared.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    private static final UUID DOMAIN_EVENT_ID =
            UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d301");
    private static final UUID REALTIME_EVENT_ID =
            UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d302");
    private static final UUID CORRELATION_ID =
            UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d303");

    @Test
    void validatesStableEventNamesAndSchemaVersions() {
        assertEquals(SchemaVersion.V1, SchemaVersion.from("1"));
        assertEquals("1", SchemaVersion.V1.toString());
        assertEquals("WORK_ITEM_STATUS_CHANGED", EventType.from(" WORK_ITEM_STATUS_CHANGED ").value());

        assertThrows(IllegalArgumentException.class, () -> SchemaVersion.from("0"));
        assertThrows(IllegalArgumentException.class, () -> SchemaVersion.from("01"));
        assertThrows(IllegalArgumentException.class, () -> EventType.from("work-item.changed"));
    }

    @Test
    void projectsOneDomainFactIntoIndependentlyIdentifiedRealtimeStreams() {
        DomainEventEnvelope<StatusChanged> domainEvent = domainEvent();

        RealtimeEventEnvelope<StatusChanged> teamEvent = RealtimeEventEnvelope.fromDomain(
                REALTIME_EVENT_ID, StreamType.TEAM, domainEvent);

        assertEquals(Optional.of(DOMAIN_EVENT_ID), teamEvent.domainEventId());
        assertEquals(Optional.of(3L), teamEvent.aggregateVersion());
        assertEquals(domainEvent.correlationId(), teamEvent.correlationId());
        assertEquals(domainEvent.payload(), teamEvent.payload());
        assertNotEquals(domainEvent.eventId(), teamEvent.eventId());
    }

    @Test
    void representsTransientAgUiProgressWithoutClaimingADomainFact() {
        RealtimeEventEnvelope<Progress> progress = RealtimeEventEnvelope.transientAgUi(
                REALTIME_EVENT_ID,
                EventType.from("TOOL_PROGRESS"),
                SchemaVersion.V1,
                CORRELATION_ID,
                Optional.empty(),
                UtcTimestamp.parse("2026-08-06T10:00:00Z"),
                new Progress(50));

        assertEquals(StreamType.AG_UI, progress.streamType());
        assertTrue(progress.domainEventId().isEmpty());
        assertTrue(progress.aggregate().isEmpty());
        assertTrue(progress.aggregateVersion().isEmpty());
    }

    @Test
    void rejectsIncompleteOrRegressingAggregateCoordinates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RealtimeEventEnvelope<>(
                        REALTIME_EVENT_ID,
                        Optional.empty(),
                        StreamType.TEAM,
                        EventType.from("WORK_ITEM_CHANGED"),
                        SchemaVersion.V1,
                        Optional.of(domainEvent().aggregate()),
                        Optional.empty(),
                        CORRELATION_ID,
                        Optional.empty(),
                        UtcTimestamp.parse("2026-08-06T10:00:00Z"),
                        new Progress(10)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DomainEventEnvelope<>(
                        DOMAIN_EVENT_ID,
                        EventType.from("WORK_ITEM_STATUS_CHANGED"),
                        SchemaVersion.V1,
                        OrganizationId.generate(),
                        Optional.empty(),
                        Optional.empty(),
                        new AggregateReference("WORK_ITEM", UUID.randomUUID()),
                        -1,
                        EventActor.anonymousService(),
                        CORRELATION_ID,
                        Optional.empty(),
                        Optional.empty(),
                        UtcTimestamp.parse("2026-08-06T10:00:00Z"),
                        new StatusChanged("READY", "IN_PROGRESS")));
    }

    private static DomainEventEnvelope<StatusChanged> domainEvent() {
        return new DomainEventEnvelope<>(
                DOMAIN_EVENT_ID,
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
                CORRELATION_ID,
                Optional.empty(),
                Optional.of("command-42"),
                UtcTimestamp.parse("2026-08-06T10:00:00Z"),
                new StatusChanged("READY", "IN_PROGRESS"));
    }

    private record StatusChanged(String previousStatus, String currentStatus) implements DomainEvent {}

    private record Progress(int percent) {}
}
