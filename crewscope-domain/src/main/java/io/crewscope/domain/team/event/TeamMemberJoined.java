package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;
import java.util.UUID;

/** Version 1 fact emitted after a Team membership and default Personal Agent are ready. */
public record TeamMemberJoined(UUID userPrincipalId, TeamJoinMethod joinMethod)
    implements DomainEvent {

  public TeamMemberJoined {
    userPrincipalId = Objects.requireNonNull(userPrincipalId, "userPrincipalId");
    joinMethod = Objects.requireNonNull(joinMethod, "joinMethod");
  }

  public static TeamMemberJoined from(TeamMember member) {
    TeamMember source = Objects.requireNonNull(member, "member");
    return new TeamMemberJoined(source.userPrincipalId().value(), source.joinMethod());
  }
}
