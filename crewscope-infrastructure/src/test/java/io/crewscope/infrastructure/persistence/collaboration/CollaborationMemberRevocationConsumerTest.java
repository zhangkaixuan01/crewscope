package io.crewscope.infrastructure.persistence.collaboration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.collaboration.CollaborationMemberRevocationSink;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer contract of the D02 event-driven revocation sweep (M11-D02, ADR-038 §3.1):
 * the four loss-causing member events (suspension, removal, leaving, role revocation —
 * the M9b demotion path) map onto one sink call naming the member and the Team, the
 * widening events (activation, role grant) and the ownership transfer never disconnect,
 * and malformed events never throw (the receipt transaction must not roll back).
 */
class CollaborationMemberRevocationConsumerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-10T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final RecordingSink sink = new RecordingSink();

    @Test
    void theFourLossCausingEventsEachSweepTheMember() {
        for (String eventType : List.of(
                CollaborationMemberRevocationConsumer.TEAM_MEMBER_SUSPENDED,
                CollaborationMemberRevocationConsumer.TEAM_MEMBER_REMOVED,
                CollaborationMemberRevocationConsumer.TEAM_MEMBER_LEFT,
                CollaborationMemberRevocationConsumer.MEMBER_ROLE_REVOKED)) {
            sink.revocations.clear();
            UUID member = UUID.randomUUID();

            consumer().consume(publication(event(eventType, member)));

            assertEquals(1, sink.revocations.size(), eventType);
            assertEquals(member, sink.revocations.get(0).userPrincipalId());
            assertEquals(organizationId, sink.revocations.get(0).organizationId());
            assertEquals(teamId, sink.revocations.get(0).team());
        }
    }

    @Test
    void wideningAndTransferEventsNeverDisconnect() {
        for (String eventType : List.of(
                "TEAM_MEMBER_ACTIVATED",
                "MEMBER_ROLE_GRANTED",
                "TEAM_OWNERSHIP_TRANSFERRED",
                "WORK_ITEM_CREATED",
                "TEAM_MEMBER_INVITED")) {
            consumer().consume(publication(event(eventType, UUID.randomUUID())));
        }

        assertTrue(sink.revocations.isEmpty(),
            "the outgoing side of a transfer stays an ACTIVE member whose subscriptions ride"
                + " resource visibility, not the OWNER role");
    }

    @Test
    void malformedEventsAreSkippedWithoutThrowing() {
        assertDoesNotThrow(() -> consumer().consume(publication("not json at all")));
        // A team-less event cannot name which Team's connections to cut.
        assertDoesNotThrow(() -> consumer().consume(publication("""
                {"eventType":"TEAM_MEMBER_SUSPENDED","organizationId":"%s",
                 "aggregateId":"%s","aggregateVersion":2,
                 "payload":{"userPrincipalId":"%s","authorizationVersionAfter":2}}
                """.formatted(organizationId.value(), UUID.randomUUID(), UUID.randomUUID()))));
        // A member-less payload names nobody to sweep.
        assertDoesNotThrow(() -> consumer().consume(publication("""
                {"eventType":"TEAM_MEMBER_SUSPENDED","organizationId":"%s","teamId":"%s",
                 "aggregateId":"%s","aggregateVersion":2,
                 "payload":{"memberId":"%s","authorizationVersionAfter":2}}
                """.formatted(organizationId.value(), teamId.value(),
                UUID.randomUUID(), UUID.randomUUID()))));
        assertTrue(sink.revocations.isEmpty());
    }

    @Test
    void aThrowingSinkIsSwallowed() {
        CollaborationMemberRevocationConsumer throwing = new CollaborationMemberRevocationConsumer(
                JsonMapper.builder().findAndAddModules().build(),
                (userPrincipalId, organization, team) -> {
                    throw new IllegalStateException("boom");
                });

        assertDoesNotThrow(() -> throwing.consume(publication(event(
                CollaborationMemberRevocationConsumer.TEAM_MEMBER_REMOVED,
                UUID.randomUUID()))));
    }

    // ------------------------------------------------------------------ fixtures

    private CollaborationMemberRevocationConsumer consumer() {
        return new CollaborationMemberRevocationConsumer(
                JsonMapper.builder().findAndAddModules().build(), sink);
    }

    private String event(String eventType, UUID userPrincipalId) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "schemaVersion": "1",
                  "organizationId": "%s",
                  "teamId": "%s",
                  "workspaceId": null,
                  "aggregateType": "TEAM_MEMBER",
                  "aggregateId": "%s",
                  "aggregateVersion": 2,
                  "actorType": "USER",
                  "actorId": "%s",
                  "correlationId": "%s",
                  "causationId": null,
                  "idempotencyKey": null,
                  "occurredAt": "2026-10-10T08:00:00Z",
                  "payload": {"memberId":"%s","userPrincipalId":"%s",
                              "authorizationVersionAfter":2}
                }
                """.formatted(UUID.randomUUID(), eventType, organizationId.value(), teamId.value(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), userPrincipalId);
    }

    private static EventPublication publication(String eventJson) {
        return new EventPublication(
                UUID.randomUUID(), UUID.randomUUID(), "crewscope.events", "partition", 1,
                NOW, eventJson);
    }

    private record Revocation(
            UUID userPrincipalId, OrganizationId organizationId, TeamId team) {}

    private static final class RecordingSink implements CollaborationMemberRevocationSink {
        private final List<Revocation> revocations = new ArrayList<>();

        @Override
        public void memberAuthorizationRevoked(
                UUID userPrincipalId, io.crewscope.domain.shared.id.OrganizationId organization,
                io.crewscope.domain.shared.id.TeamId team) {
            revocations.add(new Revocation(userPrincipalId, organization, team));
        }
    }
}
