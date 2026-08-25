package io.crewscope.application.activity;

import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Complete tenant, projection and filter scope cryptographically bound by a Team cursor codec. */
public record ActivityCursorScope(
        OrganizationId organizationId,
        TeamId teamId,
        ProjectionName projectionName,
        ProjectionGeneration projectionGeneration,
        SchemaVersion projectionSchemaVersion,
        ActivityFilterFingerprint filterFingerprint) {

    public ActivityCursorScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        projectionGeneration =
                Objects.requireNonNull(projectionGeneration, "projectionGeneration");
        projectionSchemaVersion =
                Objects.requireNonNull(projectionSchemaVersion, "projectionSchemaVersion");
        filterFingerprint = Objects.requireNonNull(filterFingerprint, "filterFingerprint");
    }

    public static ActivityCursorScope of(
            OrganizationId organizationId,
            TeamId teamId,
            ProjectionName projectionName,
            ProjectionGeneration projectionGeneration,
            SchemaVersion projectionSchemaVersion,
            ActivityFilter filter) {
        return new ActivityCursorScope(
                organizationId,
                teamId,
                projectionName,
                projectionGeneration,
                projectionSchemaVersion,
                Objects.requireNonNull(filter, "filter").fingerprint());
    }
}
