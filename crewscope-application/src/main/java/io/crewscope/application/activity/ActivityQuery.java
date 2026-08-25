package io.crewscope.application.activity;

import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Objects;
import java.util.Optional;

/** Scope-complete query used by Team and WorkItem Activity views. */
public record ActivityQuery(
        ActivityCursorScope cursorScope,
        ActivityFilter filter,
        Optional<TeamActivityCursor> after,
        int limit) {

    public static final int MAX_LIMIT = 200;

    public ActivityQuery {
        cursorScope = Objects.requireNonNull(cursorScope, "cursorScope");
        filter = Objects.requireNonNull(filter, "filter");
        after = Objects.requireNonNull(after, "after");
        if (!cursorScope.filterFingerprint().equals(filter.fingerprint())) {
            throw new IllegalArgumentException(
                    "Activity query filter must match the cursor scope fingerprint");
        }
        if (after.isPresent()) {
            after.orElseThrow().requireScope(cursorScope);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Activity query limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public static ActivityQuery team(
            OrganizationId organizationId,
            TeamId teamId,
            ProjectionName projectionName,
            ProjectionGeneration generation,
            SchemaVersion projectionSchemaVersion,
            ActivityFilter filter,
            Optional<TeamActivityCursor> after,
            int limit) {
        return create(
                organizationId,
                teamId,
                projectionName,
                generation,
                projectionSchemaVersion,
                filter,
                after,
                limit);
    }

    public static ActivityQuery workItem(
            OrganizationId organizationId,
            TeamId teamId,
            WorkItemId workItemId,
            ProjectionName projectionName,
            ProjectionGeneration generation,
            SchemaVersion projectionSchemaVersion,
            Optional<TeamActivityCursor> after,
            int limit) {
        return create(
                organizationId,
                teamId,
                projectionName,
                generation,
                projectionSchemaVersion,
                ActivityFilter.forWorkItem(workItemId),
                after,
                limit);
    }

    private static ActivityQuery create(
            OrganizationId organizationId,
            TeamId teamId,
            ProjectionName projectionName,
            ProjectionGeneration generation,
            SchemaVersion projectionSchemaVersion,
            ActivityFilter filter,
            Optional<TeamActivityCursor> after,
            int limit) {
        ActivityCursorScope scope = ActivityCursorScope.of(
                organizationId,
                teamId,
                projectionName,
                generation,
                projectionSchemaVersion,
                filter);
        return new ActivityQuery(scope, filter, after, limit);
    }
}
