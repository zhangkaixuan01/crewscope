package io.crewscope.application.team;

import java.util.Objects;

/** One effective team-scoped role grant, addressed by its revocable grant id. */
public record MemberGrantView(String id, String roleKey) {

  public MemberGrantView {
    id = Objects.requireNonNull(id, "id");
    roleKey = Objects.requireNonNull(roleKey, "roleKey");
  }
}
