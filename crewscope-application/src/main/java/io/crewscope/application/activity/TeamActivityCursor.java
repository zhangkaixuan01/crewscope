package io.crewscope.application.activity;

import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.domain.activity.ActivityEventId;
import io.crewscope.domain.activity.TeamSequence;
import java.util.Objects;

/** Scope-bound durable position in one generation of a Team Activity stream. */
public record TeamActivityCursor(
        ActivityCursorScope scope, TeamSequence teamSequence, ActivityEventId eventId) {

    public TeamActivityCursor {
        scope = Objects.requireNonNull(scope, "scope");
        teamSequence = Objects.requireNonNull(teamSequence, "teamSequence");
        eventId = Objects.requireNonNull(eventId, "eventId");
    }

    public static TeamActivityCursor from(ActivityCursorScope scope, ActivityEvent event) {
        ActivityEvent required = Objects.requireNonNull(event, "event");
        requireEventScope(Objects.requireNonNull(scope, "scope"), required);
        return new TeamActivityCursor(scope, required.teamSequence(), required.id());
    }

    /** Fails closed when a decoded cursor is replayed on another route or normalized filter. */
    public TeamActivityCursor requireScope(ActivityCursorScope expectedScope) {
        if (!scope.equals(Objects.requireNonNull(expectedScope, "expectedScope"))) {
            throw new IllegalArgumentException(
                    "Team Activity cursor does not belong to the requested scope");
        }
        return this;
    }

    public boolean isBefore(ActivityEvent event) {
        ActivityEvent required = Objects.requireNonNull(event, "event");
        requireEventScope(scope, required);
        return required.teamSequence().isAfter(teamSequence);
    }

    private static void requireEventScope(ActivityCursorScope scope, ActivityEvent event) {
        if (!scope.organizationId().equals(event.organizationId())
                || !scope.teamId().equals(event.teamId())
                || !scope.projectionName().equals(event.projectionName())
                || !scope.projectionGeneration().equals(event.projectionGeneration())
                || !scope.projectionSchemaVersion().equals(event.projectionSchemaVersion())) {
            throw new IllegalArgumentException(
                    "Activity event does not belong to the Team cursor projection scope");
        }
    }
}
