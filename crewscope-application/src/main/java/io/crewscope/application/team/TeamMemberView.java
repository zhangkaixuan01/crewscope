package io.crewscope.application.team;

import io.crewscope.domain.team.TeamMember;
import java.util.Objects;

/** Team Membership fact enriched with the authoritative USER Principal display name. */
public record TeamMemberView(TeamMember member, String displayName) {

  public TeamMemberView {
    member = Objects.requireNonNull(member, "member");
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    displayName = displayName.strip();
  }
}
