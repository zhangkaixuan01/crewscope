package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Scope-bound keyset position for any Task association direction. */
public record TaskAssociationCursor(
        OrganizationId organizationId,
        TeamId teamId,
        TaskAssociationSourceType sourceType,
        UUID sourceId,
        UtcTimestamp associatedAt,
        UUID targetId) {

    public TaskAssociationCursor {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        associatedAt = Objects.requireNonNull(associatedAt, "associatedAt");
        targetId = Objects.requireNonNull(targetId, "targetId");
    }

    /** Fails closed when an opaque cursor is replayed on another association route. */
    public TaskAssociationCursor requireSource(
            OrganizationId expectedOrganizationId,
            TeamId expectedTeamId,
            TaskAssociationSourceType expectedSourceType,
            UUID expectedSourceId) {
        if (!organizationId.equals(Objects.requireNonNull(
                        expectedOrganizationId, "expectedOrganizationId"))
                || !teamId.equals(Objects.requireNonNull(expectedTeamId, "expectedTeamId"))
                || sourceType != Objects.requireNonNull(expectedSourceType, "expectedSourceType")
                || !sourceId.equals(Objects.requireNonNull(expectedSourceId, "expectedSourceId"))) {
            throw new IllegalArgumentException("association cursor does not belong to this source");
        }
        return this;
    }
}
