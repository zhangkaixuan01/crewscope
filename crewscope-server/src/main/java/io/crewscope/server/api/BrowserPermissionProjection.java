package io.crewscope.server.api;

import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.team.TeamPermission;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Projects server-owned authorization facts into stable browser capability keys. */
final class BrowserPermissionProjection {

  private static final List<String> ACTIVE_MEMBER_CAPABILITIES = List.of(
      "scope:read",
      "team:members:read",
      "work-projects:read",
      "work:read");

  private static final Map<TeamPermission, List<String>> TEAM_CAPABILITIES = mappings();

  private BrowserPermissionProjection() {}

  /** Returns UI affordances for one active Team membership; APIs still reauthorize every request. */
  static List<String> team(Collection<TeamPermission> permissions) {
    TreeSet<String> projected = new TreeSet<>(ACTIVE_MEMBER_CAPABILITIES);
    Objects.requireNonNull(permissions, "permissions").stream()
        .map(permission -> TEAM_CAPABILITIES.getOrDefault(permission, List.of()))
        .flatMap(Collection::stream)
        .forEach(projected::add);
    return List.copyOf(projected);
  }

  /** Returns account-wide controls without merging capabilities from unrelated Teams. */
  static List<String> account(PlatformRole platformRole) {
    TreeSet<String> projected = new TreeSet<>();
    if (Objects.requireNonNull(platformRole, "platformRole") == PlatformRole.OPERATOR) {
      projected.add("operations:manage");
    }
    return List.copyOf(projected);
  }

  private static Map<TeamPermission, List<String>> mappings() {
    EnumMap<TeamPermission, List<String>> mappings = new EnumMap<>(TeamPermission.class);
    mappings.put(TeamPermission.MEMBER_MANAGE, List.of("team:members:manage"));
    mappings.put(TeamPermission.WORKSPACE_MANAGE, List.of("repositories:manage"));
    mappings.put(TeamPermission.PROVIDER_MANAGE, List.of("provider:manage"));
    mappings.put(TeamPermission.AGENT_MANAGE, List.of("agent:manage"));
    mappings.put(TeamPermission.WORK_PROJECT_MANAGE, List.of("work-projects:manage"));
    mappings.put(TeamPermission.RESPONSIBILITY_MANAGE, List.of("responsibility:manage"));
    mappings.put(TeamPermission.WORK_CREATE, List.of("work:create"));
    mappings.put(TeamPermission.WORK_PARTICIPATE, List.of("work:participate"));
    mappings.put(TeamPermission.COLLABORATION_REQUEST, List.of("conversation:use"));
    mappings.put(TeamPermission.AUDIT_READ, List.of("audit:read"));
    mappings.put(TeamPermission.GOVERNANCE_EXPORT, List.of("governance:export"));
    return Map.copyOf(mappings);
  }
}
