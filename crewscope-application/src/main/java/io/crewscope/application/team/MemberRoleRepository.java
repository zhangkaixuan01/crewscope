package io.crewscope.application.team;

import io.crewscope.domain.team.MemberRole;

/** Persistence Port for explicit TeamMember role grants. */
public interface MemberRoleRepository {

    MemberRole create(MemberRole memberRole);
}
