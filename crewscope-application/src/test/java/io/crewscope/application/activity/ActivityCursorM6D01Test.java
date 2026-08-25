package io.crewscope.application.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.activity.ActivityActor;
import io.crewscope.domain.activity.ActivityCategory;
import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.domain.activity.ActivityPayloadSchema;
import io.crewscope.domain.activity.ActivityReference;
import io.crewscope.domain.activity.ActivityReferenceType;
import io.crewscope.domain.activity.ActivitySubject;
import io.crewscope.domain.activity.ActivitySubjectType;
import io.crewscope.domain.activity.ActivityVisibility;
import io.crewscope.domain.activity.TeamSequence;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
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

class ActivityCursorM6D01Test {

    private static final OrganizationId ORGANIZATION_ID =
            OrganizationId.from("00000000-0000-0000-0000-000000000201");
    private static final TeamId TEAM_ID =
            TeamId.from("00000000-0000-0000-0000-000000000202");
    private static final WorkItemId WORK_ITEM_ID =
            WorkItemId.from("00000000-0000-0000-0000-000000000203");
    private static final PrincipalId ACTOR_ID =
            PrincipalId.from("00000000-0000-0000-0000-000000000204");
    private static final ProjectionName PROJECTION_NAME = new ProjectionName("team-activity");

    @Test
    void createsOrderIndependentFilterFingerprintAndMatchesPublicFacts() {
        ActivityFilter first = new ActivityFilter(
                Optional.of(WORK_ITEM_ID),
                Set.of(ActivityCategory.WORK_ITEM, ActivityCategory.REVIEW),
                Set.of(new EventType("REVIEW_COMPLETED"), new EventType("WORK_ITEM_STATUS_CHANGED")),
                Set.of(ACTOR_ID, PrincipalId.generate()));
        ActivityFilter reordered = new ActivityFilter(
                Optional.of(WORK_ITEM_ID),
                Set.of(ActivityCategory.REVIEW, ActivityCategory.WORK_ITEM),
                Set.of(new EventType("WORK_ITEM_STATUS_CHANGED"), new EventType("REVIEW_COMPLETED")),
                Set.copyOf(first.actorPrincipalIds()));

        assertEquals(first.fingerprint(), reordered.fingerprint());
        assertTrue(first.matches(event(1, ProjectionGeneration.FIRST, WORK_ITEM_ID)));
        assertFalse(first.matches(event(2, ProjectionGeneration.FIRST, WorkItemId.generate())));
    }

    @Test
    void rejectsCursorReuseAcrossOrganizationTeamGenerationSchemaAndFilter() {
        ActivityFilter filter = ActivityFilter.forWorkItem(WORK_ITEM_ID);
        ActivityCursorScope scope = scope(
                ORGANIZATION_ID,
                TEAM_ID,
                ProjectionGeneration.FIRST,
                SchemaVersion.V1,
                filter);
        TeamActivityCursor cursor = TeamActivityCursor.from(
                scope, event(1, ProjectionGeneration.FIRST, WORK_ITEM_ID));

        assertEquals(cursor, cursor.requireScope(scope));
        assertThrows(
                IllegalArgumentException.class,
                () -> cursor.requireScope(scope(
                        OrganizationId.generate(),
                        TEAM_ID,
                        ProjectionGeneration.FIRST,
                        SchemaVersion.V1,
                        filter)));
        assertThrows(
                IllegalArgumentException.class,
                () -> cursor.requireScope(scope(
                        ORGANIZATION_ID,
                        TeamId.generate(),
                        ProjectionGeneration.FIRST,
                        SchemaVersion.V1,
                        filter)));
        assertThrows(
                IllegalArgumentException.class,
                () -> cursor.requireScope(scope(
                        ORGANIZATION_ID,
                        TEAM_ID,
                        new ProjectionGeneration(2),
                        SchemaVersion.V1,
                        filter)));
        assertThrows(
                IllegalArgumentException.class,
                () -> cursor.requireScope(scope(
                        ORGANIZATION_ID,
                        TEAM_ID,
                        ProjectionGeneration.FIRST,
                        SchemaVersion.V2,
                        filter)));
        assertThrows(
                IllegalArgumentException.class,
                () -> cursor.requireScope(scope(
                        ORGANIZATION_ID,
                        TEAM_ID,
                        ProjectionGeneration.FIRST,
                        SchemaVersion.V1,
                        ActivityFilter.ALL)));
    }

    @Test
    void rejectsTamperedFilterScopeBeforeRepositoryAccess() {
        ActivityFilter workItemFilter = ActivityFilter.forWorkItem(WORK_ITEM_ID);
        ActivityCursorScope legitimate = scope(
                ORGANIZATION_ID,
                TEAM_ID,
                ProjectionGeneration.FIRST,
                SchemaVersion.V1,
                workItemFilter);
        TeamActivityCursor cursor = TeamActivityCursor.from(
                legitimate, event(1, ProjectionGeneration.FIRST, WORK_ITEM_ID));
        ActivityCursorScope tampered = new ActivityCursorScope(
                legitimate.organizationId(),
                legitimate.teamId(),
                legitimate.projectionName(),
                legitimate.projectionGeneration(),
                legitimate.projectionSchemaVersion(),
                ActivityFilter.ALL.fingerprint());

        assertThrows(
                IllegalArgumentException.class,
                () -> new ActivityQuery(tampered, workItemFilter, Optional.of(cursor), 50));
    }

    @Test
    void validatesStrictTeamSequenceAndPageScope() {
        ActivityQuery query = ActivityQuery.team(
                ORGANIZATION_ID,
                TEAM_ID,
                PROJECTION_NAME,
                ProjectionGeneration.FIRST,
                SchemaVersion.V1,
                ActivityFilter.ALL,
                Optional.empty(),
                10);
        ActivityEvent first = event(1, ProjectionGeneration.FIRST, WORK_ITEM_ID);
        ActivityEvent second = event(2, ProjectionGeneration.FIRST, WORK_ITEM_ID);
        ActivityPage page = new ActivityPage(query, List.of(first, second), true);

        assertEquals(second.id(), page.nextCursor().orElseThrow().eventId());
        assertEquals(new TeamSequence(2), page.resumeCursor().orElseThrow().teamSequence());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActivityPage(query, List.of(second, first), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActivityPage(
                        query,
                        List.of(event(1, new ProjectionGeneration(2), WORK_ITEM_ID)),
                        false));
    }

    @Test
    void teamAndWorkItemQueriesReturnTheSameCanonicalActivityIdentity() {
        ActivityEvent historical = event(1, ProjectionGeneration.FIRST, WORK_ITEM_ID);
        ActivityQuery teamQuery = ActivityQuery.team(
                ORGANIZATION_ID,
                TEAM_ID,
                PROJECTION_NAME,
                ProjectionGeneration.FIRST,
                SchemaVersion.V1,
                ActivityFilter.ALL,
                Optional.empty(),
                10);
        ActivityQuery workItemQuery = ActivityQuery.workItem(
                ORGANIZATION_ID,
                TEAM_ID,
                WORK_ITEM_ID,
                PROJECTION_NAME,
                ProjectionGeneration.FIRST,
                SchemaVersion.V1,
                Optional.empty(),
                10);
        ActivityEvent rebuilt = event(1, new ProjectionGeneration(2), WORK_ITEM_ID);

        ActivityEvent teamResult = new ActivityPage(teamQuery, List.of(historical), false)
                .events()
                .get(0);
        ActivityEvent workItemResult = new ActivityPage(workItemQuery, List.of(historical), false)
                .events()
                .get(0);

        assertEquals(teamResult.id(), workItemResult.id());
        assertEquals(teamResult.domainEventId(), workItemResult.domainEventId());
        assertEquals(historical.id(), rebuilt.id());
    }

    @Test
    void resumesOnlyAfterTheLastAppliedCursorPosition() {
        ActivityQuery initial = ActivityQuery.team(
                ORGANIZATION_ID,
                TEAM_ID,
                PROJECTION_NAME,
                ProjectionGeneration.FIRST,
                SchemaVersion.V1,
                ActivityFilter.ALL,
                Optional.empty(),
                10);
        TeamActivityCursor cursor = new ActivityPage(
                        initial,
                        List.of(event(1, ProjectionGeneration.FIRST, WORK_ITEM_ID)),
                        false)
                .resumeCursor()
                .orElseThrow();
        ActivityQuery resumed = ActivityQuery.team(
                ORGANIZATION_ID,
                TEAM_ID,
                PROJECTION_NAME,
                ProjectionGeneration.FIRST,
                SchemaVersion.V1,
                ActivityFilter.ALL,
                Optional.of(cursor),
                10);

        assertTrue(cursor.isBefore(event(2, ProjectionGeneration.FIRST, WORK_ITEM_ID)));
        assertFalse(cursor.isBefore(event(1, ProjectionGeneration.FIRST, WORK_ITEM_ID)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActivityPage(
                        resumed,
                        List.of(event(1, ProjectionGeneration.FIRST, WORK_ITEM_ID)),
                        false));
    }

    private static ActivityEvent event(
            long sequence, ProjectionGeneration generation, WorkItemId workItemId) {
        UUID domainEventId = UUID.fromString(String.format(
                "00000000-0000-0000-0000-%012d", 300 + sequence));
        ActivityPayloadSchema schema = new ActivityPayloadSchema(
                "work-item.changed",
                SchemaVersion.V1,
                Set.of("workItemKey"),
                Set.of("status"));
        return ActivityEvent.project(
                domainEventId,
                ORGANIZATION_ID,
                TEAM_ID,
                PROJECTION_NAME,
                generation,
                SchemaVersion.V1,
                new TeamSequence(sequence),
                new EventType("WORK_ITEM_STATUS_CHANGED"),
                ActivityCategory.WORK_ITEM,
                ActivityVisibility.TEAM_MEMBERS,
                new ActivitySubject(ActivitySubjectType.WORK_ITEM, workItemId.value()),
                new ActivityActor(EventActorType.USER, Optional.of(ACTOR_ID)),
                List.of(
                        new ActivityReference(ActivityReferenceType.TEAM, TEAM_ID.value()),
                        new ActivityReference(ActivityReferenceType.WORK_ITEM, workItemId.value())),
                UtcTimestamp.parse("2026-08-25T08:00:00Z"),
                schema.createPayload(Map.of("workItemKey", "CS-42", "status", "OPEN")));
    }

    private static ActivityCursorScope scope(
            OrganizationId organizationId,
            TeamId teamId,
            ProjectionGeneration generation,
            SchemaVersion schemaVersion,
            ActivityFilter filter) {
        return ActivityCursorScope.of(
                organizationId, teamId, PROJECTION_NAME, generation, schemaVersion, filter);
    }
}
