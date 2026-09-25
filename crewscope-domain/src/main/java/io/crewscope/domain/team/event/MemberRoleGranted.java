package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after a TeamRole grant became effective for a member. */
public record MemberRoleGranted(
    UUID memberId, UUID userPrincipalId, String roleKey, long authorizationVersionAfter)
    implements DomainEvent {

  public MemberRoleGranted {
    memberId = Objects.requireNonNull(memberId, "memberId");
    userPrincipalId = Objects.requireNonNull(userPrincipalId, "userPrincipalId");
    roleKey = Objects.requireNonNull(roleKey, "roleKey");
  }

  public static MemberRoleGranted from(TeamMember member, String roleKey) {
    TeamMember source = Objects.requireNonNull(member, "member");
    return new MemberRoleGranted(
        source.id().value(),
        source.userPrincipalId().value(),
        Objects.requireNonNull(roleKey, "roleKey"),
        source.authorizationVersion());
  }
}
