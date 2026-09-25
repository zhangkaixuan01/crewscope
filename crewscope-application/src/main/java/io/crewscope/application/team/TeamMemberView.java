package io.crewscope.application.team;

import io.crewscope.domain.team.TeamMember;
import java.util.List;
import java.util.Objects;

/** Team Membership fact enriched with the authoritative USER Principal display name. */
public record TeamMemberView(
    TeamMember member, String displayName, List<String> roles, List<MemberGrantView> grants) {

  /** Compatibility constructor for callers that only need the display name. */
  public TeamMemberView(TeamMember member, String displayName) {
    this(member, displayName, List.of(), List.of());
  }

  public TeamMemberView {
    member = Objects.requireNonNull(member, "member");
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    displayName = displayName.strip();
    roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
    grants = List.copyOf(Objects.requireNonNull(grants, "grants"));
  }
}
