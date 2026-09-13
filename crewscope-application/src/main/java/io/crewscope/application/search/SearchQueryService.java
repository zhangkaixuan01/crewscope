package io.crewscope.application.search;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.search.SearchableObjectType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Member-facing search facade with bounded input and server-resolved identity. */
public final class SearchQueryService {
  private final SearchIndexPort index; private final SearchAccessPolicy access;
  public SearchQueryService(SearchIndexPort index, SearchAccessPolicy access) { this.index = Objects.requireNonNull(index, "index"); this.access = Objects.requireNonNull(access, "access"); }
  public SearchResultPage search(TeamAccessContext context, OrganizationId organizationId, TeamId teamId, String text, Optional<WorkProjectId> projectId, Set<SearchableObjectType> types, Optional<SearchCursor> after, int limit) {
    var member = access.requireMember(context, organizationId, teamId);
    return index.search(new SearchQuery(organizationId, teamId, member.id(), member.userPrincipalId(), projectId, text, types, after, limit));
  }
}
