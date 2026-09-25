package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after a member chose to leave the Team from their own account. */
public record TeamMemberLeft(
    UUID memberId, UUID userPrincipalId, long authorizationVersionAfter) implements DomainEvent {

  public TeamMemberLeft {
    memberId = Objects.requireNonNull(memberId, "memberId");
    userPrincipalId = Objects.requireNonNull(userPrincipalId, "userPrincipalId");
  }

  public static TeamMemberLeft from(TeamMember member) {
    TeamMember source = Objects.requireNonNull(member, "member");
    return new TeamMemberLeft(
        source.id().value(), source.userPrincipalId().value(), source.authorizationVersion());
  }
}
