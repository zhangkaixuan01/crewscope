package io.crewscope.application.team;

import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Optional;

/** Persistence Port for durable Team memberships. */
public interface TeamMemberRepository {

    TeamMember create(TeamMember member);

    /** Commits one membership lifecycle change with an optimistic version predicate. */
    default TeamMember update(TeamMember member) {
        throw new UnsupportedOperationException("TeamMember update is not implemented");
    }

    /** Finds one membership only inside the explicit Organization boundary. */
    default Optional<TeamMember> findById(OrganizationId organizationId, TeamMemberId id) {
        throw new UnsupportedOperationException("TeamMember lookup is not implemented");
    }

    /** Resolves the current membership for one USER Principal inside an exact Team scope. */
    default Optional<TeamMember> findByTeamAndUserPrincipalId(
            OrganizationId organizationId, TeamId teamId, PrincipalId userPrincipalId) {
        throw new UnsupportedOperationException("TeamMember principal lookup is not implemented");
    }
}
