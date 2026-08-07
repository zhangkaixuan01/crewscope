package io.crewscope.application.team;

import io.crewscope.domain.team.TeamMember;

/** Persistence Port for durable Team memberships. */
public interface TeamMemberRepository {

    TeamMember create(TeamMember member);
}
