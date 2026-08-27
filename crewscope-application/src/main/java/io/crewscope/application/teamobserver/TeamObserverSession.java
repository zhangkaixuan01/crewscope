package io.crewscope.application.teamobserver;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;

/** Public-safe metadata of one member-bound, read-only Team Observer session. */
public record TeamObserverSession(
        TeamObserverSessionId id,
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId memberId,
        PrincipalId actorId,
        AgentProfileId observerProfileId,
        UtcTimestamp createdAt) {

    public TeamObserverSession {
        id = Objects.requireNonNull(id, "id");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        memberId = Objects.requireNonNull(memberId, "memberId");
        actorId = Objects.requireNonNull(actorId, "actorId");
        observerProfileId = Objects.requireNonNull(observerProfileId, "observerProfileId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
