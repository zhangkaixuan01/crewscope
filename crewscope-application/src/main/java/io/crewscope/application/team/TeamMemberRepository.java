package io.crewscope.application.team;

import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.shared.id.OrganizationId;
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
}
