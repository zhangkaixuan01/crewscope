package io.crewscope.application.workitem;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One read of a member's Team roles and grants, reused to answer the WorkItem transition permission
 * for any number of projects.
 *
 * <p>A WorkDesk page asks the same question — may this member participate in this item's project? —
 * once per row, and a member's grants cover a whole Team with at most one WorkProject scope each, so
 * every project's answer can be computed from a single read. Asking
 * {@link WorkItemAccessPolicy#hasPermission} per row would instead re-read the Team, the membership,
 * the project and both grant tables for every item, turning one page into an N+1.
 *
 * <p>The decision itself is not repeated here: {@link #granted} delegates to the same
 * {@code WorkItemAccessPolicy} rule that the transition command path uses, so a projection and a
 * command can never disagree about whether a member may participate.
 */
public final class WorkItemTransitionPermissionResolver {

  private static final WorkItemTransitionPermissionResolver UNRESTRICTED =
      new WorkItemTransitionPermissionResolver(null, Map.of(), List.of(), null);

  private final TeamMemberId memberId;
  private final Map<TeamRoleId, TeamRole> roles;
  private final List<MemberRole> grants;
  private final UtcTimestamp occurredAt;

  private WorkItemTransitionPermissionResolver(
      TeamMemberId memberId,
      Map<TeamRoleId, TeamRole> roles,
      List<MemberRole> grants,
      UtcTimestamp occurredAt) {
    this.memberId = memberId;
    this.roles = Objects.requireNonNull(roles, "roles");
    this.grants = List.copyOf(Objects.requireNonNull(grants, "grants"));
    this.occurredAt = occurredAt;
  }

  /** A resolver for a platform administrator, who holds every permission in every project. */
  public static WorkItemTransitionPermissionResolver unrestricted() {
    return UNRESTRICTED;
  }

  /** A resolver over facts already read for one member; the caller owns the scoping of both. */
  public static WorkItemTransitionPermissionResolver forMember(
      TeamMemberId memberId,
      Map<TeamRoleId, TeamRole> roles,
      List<MemberRole> grants,
      UtcTimestamp occurredAt) {
    return new WorkItemTransitionPermissionResolver(
        Objects.requireNonNull(memberId, "memberId"),
        roles,
        grants,
        Objects.requireNonNull(occurredAt, "occurredAt"));
  }

  /**
   * True when the resolver stands in for platform authority rather than a Team member; callers that
   * validate a project before deciding (as {@link WorkItemAccessPolicy#hasPermission} does) must
   * skip that validation for exactly the same principals the policy used to skip it for.
   */
  public boolean isUnrestricted() {
    return memberId == null;
  }

  /** Answers the permission question for one project without touching any repository. */
  public boolean granted(WorkProjectId projectId, TeamPermission permission) {
    if (isUnrestricted()) {
      return true;
    }
    return WorkItemAccessPolicy.granted(
        roles, grants, Objects.requireNonNull(projectId, "projectId"), permission, occurredAt);
  }
}
