package io.crewscope.application.search;

import io.crewscope.domain.search.SearchableObjectType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Validated member search input; all scope and visibility coordinates are server-owned. */
public record SearchQuery(
    OrganizationId organizationId, TeamId teamId, TeamMemberId memberId, PrincipalId principalId,
    Optional<WorkProjectId> projectId, String text, Set<SearchableObjectType> types,
    Optional<SearchCursor> after, int limit) {
  public SearchQuery {
    organizationId = Objects.requireNonNull(organizationId, "organizationId");
    teamId = Objects.requireNonNull(teamId, "teamId");
    memberId = Objects.requireNonNull(memberId, "memberId");
    principalId = Objects.requireNonNull(principalId, "principalId");
    projectId = Objects.requireNonNull(projectId, "projectId");
    text = Objects.requireNonNull(text, "text").strip();
    if (text.isBlank() || text.length() > 100) throw new IllegalArgumentException("search text must be 1..100 characters");
    types = types == null || types.isEmpty() ? EnumSet.allOf(SearchableObjectType.class) : EnumSet.copyOf(types);
    after = Objects.requireNonNull(after, "after");
    if (limit < 1 || limit > 50) throw new IllegalArgumentException("search limit must be 1..50");
  }
}
