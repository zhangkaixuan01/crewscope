package io.crewscope.application.team;

import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for explicit TeamMember role grants. */
public interface MemberRoleRepository {

    MemberRole create(MemberRole memberRole);

    /** Commits revoke or expiry with an optimistic version predicate. */
    default MemberRole update(MemberRole memberRole) {
        throw new UnsupportedOperationException("MemberRole update is not implemented");
    }

    default Optional<MemberRole> findById(OrganizationId organizationId, MemberRoleId id) {
        throw new UnsupportedOperationException("MemberRole lookup is not implemented");
    }

    default List<MemberRole> findByMember(
            OrganizationId organizationId, TeamMemberId memberId) {
        throw new UnsupportedOperationException("MemberRole list is not implemented");
    }
}
