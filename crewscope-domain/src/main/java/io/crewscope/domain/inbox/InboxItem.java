package io.crewscope.domain.inbox;

import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;

/** Generation-aware projection row whose identity remains stable across rebuilds. */
public record InboxItem(
        InboxItemId id,
        TeamId teamId,
        ProjectionName projectionName,
        ProjectionGeneration projectionGeneration,
        SchemaVersion projectionSchemaVersion,
        InboxSource source) {

    public InboxItem {
        source = Objects.requireNonNull(source, "source");
        id = Objects.requireNonNull(id, "id").requireSource(source.key());
        teamId = Objects.requireNonNull(teamId, "teamId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        projectionGeneration = Objects.requireNonNull(projectionGeneration, "projectionGeneration");
        projectionSchemaVersion =
                Objects.requireNonNull(projectionSchemaVersion, "projectionSchemaVersion");
    }

    public static InboxItem project(
            TeamId teamId,
            ProjectionName projectionName,
            ProjectionGeneration projectionGeneration,
            SchemaVersion projectionSchemaVersion,
            InboxSource source) {
        InboxSource required = Objects.requireNonNull(source, "source");
        return new InboxItem(
                InboxItemId.fromSource(required.key()),
                teamId,
                projectionName,
                projectionGeneration,
                projectionSchemaVersion,
                required);
    }

    public InboxItem close(InboxCloseReason reason, UtcTimestamp occurredAt) {
        InboxSource closedSource = source.close(reason, occurredAt);
        if (closedSource == source) {
            return this;
        }
        return new InboxItem(
                id,
                teamId,
                projectionName,
                projectionGeneration,
                projectionSchemaVersion,
                closedSource);
    }

    public OrganizationId organizationId() {
        return source.key().organizationId();
    }

    public TeamMemberId memberId() {
        return source.key().memberId();
    }
}
