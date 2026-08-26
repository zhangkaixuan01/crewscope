package io.crewscope.application.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Proves snapshot high-water, filter and Generation invariants before persistence adapters exist. */
class TeamActivitySnapshotM6E05Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final WorkItemId WORK_ITEM_ID = WorkItemId.generate();
    private static final PrincipalId ACTOR_ID = PrincipalId.generate();
    private static final ProjectionName PROJECTION_NAME = new ProjectionName("team-activity");
    private static final ActivityFilter FILTER = ActivityFilter.ALL;
    private static final ActivityCursorScope SCOPE = ActivityCursorScope.of(
            ORGANIZATION_ID,
            TEAM_ID,
            PROJECTION_NAME,
            ProjectionGeneration.FIRST,
            SchemaVersion.V1,
            FILTER);

    @Test
    void acceptsFilteredRowsAndAHighWaterAfterTheLastVisibleRow() {
        TeamActivitySnapshotRequest request = request(FILTER);
        ActivityEvent first = event(1, TEAM_ID, ProjectionGeneration.FIRST);
        TeamActivityCursor highWater = new TeamActivityCursor(
                SCOPE, new TeamSequence(9), first.id());

        TeamActivitySnapshot snapshot = new TeamActivitySnapshot(
                request, SCOPE, List.of(first), Optional.of(highWater), false);

        assertEquals(new TeamSequence(9),
                snapshot.snapshotCursor().orElseThrow().teamSequence());
    }

    @Test
    void acceptsAnEmptyFilteredSnapshotWithAnIndependentHighWater() {
        TeamActivityCursor highWater = new TeamActivityCursor(
                SCOPE, new TeamSequence(7), event(7, TEAM_ID, ProjectionGeneration.FIRST).id());

        TeamActivitySnapshot snapshot = new TeamActivitySnapshot(
                request(FILTER), SCOPE, List.of(), Optional.of(highWater), false);

        assertEquals(List.of(), snapshot.events());
        assertEquals(new TeamSequence(7),
                snapshot.snapshotCursor().orElseThrow().teamSequence());
    }

    @Test
    void rejectsMixedTeamGenerationAndRowsAfterTheHighWater() {
        ActivityEvent first = event(1, TEAM_ID, ProjectionGeneration.FIRST);
        TeamActivityCursor firstCursor = TeamActivityCursor.from(SCOPE, first);

        assertThrows(
                IllegalArgumentException.class,
                () -> new TeamActivitySnapshot(
                        request(FILTER), SCOPE, List.of(first), Optional.empty(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TeamActivitySnapshot(
                        request(FILTER),
                        SCOPE,
                        List.of(event(2, TeamId.generate(), ProjectionGeneration.FIRST)),
                        Optional.of(firstCursor),
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TeamActivitySnapshot(
                        request(FILTER),
                        SCOPE,
                        List.of(event(2, TEAM_ID, new ProjectionGeneration(2))),
                        Optional.of(firstCursor),
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TeamActivitySnapshot(
                        request(FILTER),
                        SCOPE,
                        List.of(event(2, TEAM_ID, ProjectionGeneration.FIRST)),
                        Optional.of(firstCursor),
                        false));
    }

    private static TeamActivitySnapshotRequest request(ActivityFilter filter) {
        return new TeamActivitySnapshotRequest(
                ORGANIZATION_ID, TEAM_ID, PROJECTION_NAME, filter, 10);
    }

    private static ActivityEvent event(
            long sequence, TeamId teamId, ProjectionGeneration generation) {
        UUID domainEventId = UUID.nameUUIDFromBytes(
                ("m6-e05-" + sequence).getBytes(StandardCharsets.UTF_8));
        ActivityPayloadSchema schema = new ActivityPayloadSchema(
                "work-item.changed", SchemaVersion.V1, Set.of("workItemKey"), Set.of());
        return ActivityEvent.project(
                domainEventId,
                ORGANIZATION_ID,
                teamId,
                PROJECTION_NAME,
                generation,
                SchemaVersion.V1,
                new TeamSequence(sequence),
                new EventType("WORK_ITEM_STATUS_CHANGED"),
                ActivityCategory.WORK_ITEM,
                ActivityVisibility.TEAM_MEMBERS,
                new ActivitySubject(ActivitySubjectType.WORK_ITEM, WORK_ITEM_ID.value()),
                new ActivityActor(EventActorType.USER, Optional.of(ACTOR_ID)),
                List.of(new ActivityReference(ActivityReferenceType.TEAM, teamId.value())),
                UtcTimestamp.parse("2026-08-26T01:00:00Z"),
                schema.createPayload(Map.of("workItemKey", "CS-42")));
    }
}
