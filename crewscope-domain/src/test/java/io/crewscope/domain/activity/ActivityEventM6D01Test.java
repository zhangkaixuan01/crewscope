package io.crewscope.domain.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivityEventM6D01Test {

    private static final OrganizationId ORGANIZATION_ID =
            OrganizationId.from("00000000-0000-0000-0000-000000000101");
    private static final TeamId TEAM_ID =
            TeamId.from("00000000-0000-0000-0000-000000000102");
    private static final WorkItemId WORK_ITEM_ID =
            WorkItemId.from("00000000-0000-0000-0000-000000000103");
    private static final PrincipalId ACTOR_ID =
            PrincipalId.from("00000000-0000-0000-0000-000000000104");
    private static final UUID DOMAIN_EVENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000105");
    private static final UtcTimestamp OCCURRED_AT =
            UtcTimestamp.parse("2026-08-25T08:00:00Z");

    @Test
    void derivesStableIdentityAcrossProjectionGenerations() {
        ActivityEvent first = event(
                DOMAIN_EVENT_ID,
                ProjectionGeneration.FIRST,
                TeamSequence.FIRST,
                ActivityVisibility.TEAM_MEMBERS);
        ActivityEvent rebuilt = event(
                DOMAIN_EVENT_ID,
                ProjectionGeneration.FIRST.next(),
                TeamSequence.FIRST,
                ActivityVisibility.TEAM_MEMBERS);

        assertEquals(first.id(), rebuilt.id());
        assertEquals(first.domainEventId(), rebuilt.domainEventId());
        assertNotEquals(first.projectionGeneration(), rebuilt.projectionGeneration());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActivityEvent(
                        new ActivityEventId(UUID.randomUUID()),
                        first.domainEventId(),
                        first.organizationId(),
                        first.teamId(),
                        first.projectionName(),
                        first.projectionGeneration(),
                        first.projectionSchemaVersion(),
                        first.teamSequence(),
                        first.eventType(),
                        first.category(),
                        first.visibility(),
                        first.subject(),
                        first.actor(),
                        first.references(),
                        first.occurredAt(),
                        first.payload()));
    }

    @Test
    void enforcesExplicitPublicPayloadWhitelistAndSafeValues() {
        ActivityPayloadSchema schema = schemaV1();
        ActivityPublicPayload payload = schema.createPayload(Map.of(
                "workItemKey", "  CS-42  ",
                "status", "IN_PROGRESS"));
        ActivityPublicPayload emoji = schema.createPayload(Map.of(
                "workItemKey", "CS-43",
                "status", "DONE 🚀"));

        assertEquals(SchemaVersion.V1, payload.schema().version());
        assertEquals("CS-42", payload.values().get("workItemKey"));
        assertEquals("DONE 🚀", emoji.values().get("status"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> payload.values().put("status", "DONE"));
        assertThrows(
                DomainValidationException.class,
                () -> schema.createPayload(Map.of("workItemKey", "CS-42", "rawPayload", "{}")));
        assertThrows(
                DomainValidationException.class,
                () -> schema.createPayload(Map.of("status", "DONE")));
        assertThrows(
                DomainValidationException.class,
                () -> schema.createPayload(Map.of(
                        "workItemKey", "CS-42",
                        "status", "DONE\u202e")));
        assertThrows(
                DomainValidationException.class,
                () -> new ActivityPayloadSchema(
                        "work-item.leaked",
                        SchemaVersion.V1,
                        Set.of("credentialToken"),
                        Set.of()));
    }

    @Test
    void supportsOnlyAdditivePayloadSchemaEvolutionAndKeepsHistoricalVersion() {
        ActivityPayloadSchema first = schemaV1();
        ActivityPublicPayload historical = first.createPayload(Map.of(
                "workItemKey", "CS-42",
                "status", "OPEN"));
        ActivityPayloadSchema second = new ActivityPayloadSchema(
                "work-item.changed",
                SchemaVersion.V2,
                Set.of("workItemKey"),
                Set.of("status", "priority"));
        ActivityPayloadSchema breaking = new ActivityPayloadSchema(
                "work-item.changed",
                new SchemaVersion(3),
                Set.of("workItemKey", "priority"),
                Set.of("status"));

        assertTrue(second.isCompatibleSuccessorOf(first));
        assertFalse(breaking.isCompatibleSuccessorOf(second));
        assertEquals(SchemaVersion.V1, historical.schema().version());
        assertThrows(
                DomainValidationException.class,
                () -> first.createPayload(Map.of(
                        "workItemKey", "CS-42",
                        "status", "OPEN",
                        "priority", "HIGH")));
    }

    @Test
    void evaluatesTeamParticipantAndAdminVisibilityWithTenantIsolation() {
        ActivityVisibilityPolicy policy = new ActivityVisibilityPolicy();
        ActivityEvent teamEvent = event(
                DOMAIN_EVENT_ID,
                ProjectionGeneration.FIRST,
                TeamSequence.FIRST,
                ActivityVisibility.TEAM_MEMBERS);
        ActivityEvent participantEvent = event(
                UUID.fromString("00000000-0000-0000-0000-000000000106"),
                ProjectionGeneration.FIRST,
                new TeamSequence(2),
                ActivityVisibility.WORK_ITEM_PARTICIPANTS);
        ActivityEvent adminEvent = event(
                UUID.fromString("00000000-0000-0000-0000-000000000107"),
                ProjectionGeneration.FIRST,
                new TeamSequence(3),
                ActivityVisibility.TEAM_ADMINS);
        ActivityViewer member = viewer(ORGANIZATION_ID, TEAM_ID, false, Set.of());
        ActivityViewer participant = viewer(
                ORGANIZATION_ID, TEAM_ID, false, Set.of(WORK_ITEM_ID));
        ActivityViewer admin = viewer(ORGANIZATION_ID, TEAM_ID, true, Set.of());
        ActivityViewer foreignTeam = viewer(
                ORGANIZATION_ID, TeamId.generate(), true, Set.of(WORK_ITEM_ID));
        ActivityViewer foreignOrganization = viewer(
                OrganizationId.generate(), TEAM_ID, true, Set.of(WORK_ITEM_ID));

        assertTrue(policy.canView(teamEvent, member));
        assertFalse(policy.canView(participantEvent, member));
        assertTrue(policy.canView(participantEvent, participant));
        assertTrue(policy.canView(participantEvent, admin));
        assertFalse(policy.canView(adminEvent, participant));
        assertTrue(policy.canView(adminEvent, admin));
        assertFalse(policy.canView(teamEvent, foreignTeam));
        assertFalse(policy.canView(teamEvent, foreignOrganization));
        assertFalse(policy.canView(teamEvent, new ActivityViewer(
                ORGANIZATION_ID, TEAM_ID, false, false, Set.of(WORK_ITEM_ID))));
    }

    @Test
    void rejectsForeignTeamSubjectReferenceAndAmbiguousRestrictedWorkItems() {
        ActivityEvent source = event(
                DOMAIN_EVENT_ID,
                ProjectionGeneration.FIRST,
                TeamSequence.FIRST,
                ActivityVisibility.TEAM_MEMBERS);

        assertThrows(
                IllegalArgumentException.class,
                () -> copy(
                        source,
                        ActivityVisibility.TEAM_MEMBERS,
                        new ActivitySubject(ActivitySubjectType.TEAM, TeamId.generate().value()),
                        source.references()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(
                        source,
                        ActivityVisibility.TEAM_MEMBERS,
                        source.subject(),
                        List.of(new ActivityReference(
                                ActivityReferenceType.TEAM, TeamId.generate().value()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(
                        source,
                        ActivityVisibility.WORK_ITEM_PARTICIPANTS,
                        source.subject(),
                        List.of(
                                new ActivityReference(
                                        ActivityReferenceType.WORK_ITEM, WORK_ITEM_ID.value()),
                                new ActivityReference(
                                        ActivityReferenceType.WORK_ITEM,
                                        WorkItemId.generate().value()))));
    }

    @Test
    void maintainsPositiveMonotonicValueObjects() {
        assertEquals(new TeamSequence(2), TeamSequence.FIRST.next());
        assertTrue(new TeamSequence(2).isAfter(TeamSequence.FIRST));
        assertThrows(IllegalArgumentException.class, () -> new TeamSequence(0));
        assertThrows(IllegalStateException.class, () -> new TeamSequence(Long.MAX_VALUE).next());
        assertEquals(new ProjectionGeneration(2), ProjectionGeneration.FIRST.next());
        assertThrows(IllegalArgumentException.class, () -> new ProjectionGeneration(0));
    }

    private static ActivityEvent event(
            UUID domainEventId,
            ProjectionGeneration generation,
            TeamSequence sequence,
            ActivityVisibility visibility) {
        return ActivityEvent.project(
                domainEventId,
                ORGANIZATION_ID,
                TEAM_ID,
                new ProjectionName("team-activity"),
                generation,
                SchemaVersion.V1,
                sequence,
                new EventType("WORK_ITEM_STATUS_CHANGED"),
                ActivityCategory.WORK_ITEM,
                visibility,
                new ActivitySubject(ActivitySubjectType.WORK_ITEM, WORK_ITEM_ID.value()),
                new ActivityActor(EventActorType.USER, Optional.of(ACTOR_ID)),
                List.of(
                        new ActivityReference(ActivityReferenceType.TEAM, TEAM_ID.value()),
                        new ActivityReference(
                                ActivityReferenceType.WORK_ITEM, WORK_ITEM_ID.value())),
                OCCURRED_AT,
                schemaV1().createPayload(Map.of(
                        "workItemKey", "CS-42",
                        "status", "IN_PROGRESS")));
    }

    private static ActivityPayloadSchema schemaV1() {
        return new ActivityPayloadSchema(
                "work-item.changed",
                SchemaVersion.V1,
                Set.of("workItemKey"),
                Set.of("status"));
    }

    private static ActivityViewer viewer(
            OrganizationId organizationId,
            TeamId teamId,
            boolean teamAdmin,
            Set<WorkItemId> workItems) {
        return new ActivityViewer(organizationId, teamId, true, teamAdmin, workItems);
    }

    private static ActivityEvent copy(
            ActivityEvent source,
            ActivityVisibility visibility,
            ActivitySubject subject,
            List<ActivityReference> references) {
        return new ActivityEvent(
                source.id(),
                source.domainEventId(),
                source.organizationId(),
                source.teamId(),
                source.projectionName(),
                source.projectionGeneration(),
                source.projectionSchemaVersion(),
                source.teamSequence(),
                source.eventType(),
                source.category(),
                visibility,
                subject,
                source.actor(),
                references,
                source.occurredAt(),
                source.payload());
    }
}
