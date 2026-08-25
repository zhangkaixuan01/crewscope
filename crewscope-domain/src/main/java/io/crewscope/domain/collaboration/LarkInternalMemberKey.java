package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;

/** Unique internal side of one active Lark mapping. */
public record LarkInternalMemberKey(
        OrganizationId organizationId, TeamId teamId, TeamMemberId memberId) {
    public LarkInternalMemberKey {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        memberId = Objects.requireNonNull(memberId, "memberId");
    }
}
