package io.crewscope.server.api;

import io.crewscope.application.search.SearchCursor;
import io.crewscope.application.search.SearchQueryService;
import io.crewscope.application.search.SearchResultItem;
import io.crewscope.application.search.SearchResultPage;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.search.SearchableObjectType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Member-facing unified search across six safe object types. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/search")
public final class SearchController {
  private final SearchQueryService service; private final TeamRequestIdentityResolver identityResolver; private final SearchCursorCodec cursors = new SearchCursorCodec();
  public SearchController(SearchQueryService service, TeamRequestIdentityResolver identityResolver) { this.service = service; this.identityResolver = identityResolver; }
  @GetMapping
  public Mono<ResponseEntity<SearchPageResponse>> search(@PathVariable String organizationId, @PathVariable String teamId, @RequestParam String q, @RequestParam(required = false) String projectId, @RequestParam(required = false) List<String> types, @RequestParam(required = false) String after, @RequestParam(defaultValue = "20") int limit, Authentication authentication, ServerWebExchange exchange) {
    OrganizationId organization = SearchApiSupport.organization(organizationId); TeamId team = SearchApiSupport.team(teamId); String text = SearchApiSupport.text(q); Optional<WorkProjectId> project = SearchApiSupport.project(projectId); Set<SearchableObjectType> kindSet = SearchApiSupport.types(types); Optional<SearchCursor> cursor = after == null ? Optional.empty() : Optional.of(cursors.decode(after));
    if (limit < 1 || limit > 50) throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid_request", "limit must be between 1 and 50", java.util.Map.of("field", "limit"));
    return identityResolver.resolve(authentication, organization, ApiCorrelationIds.resolve(exchange)).flatMap(access -> blocking(() -> service.search(access, organization, team, text, project, kindSet, cursor, limit))).map(page -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SearchPageResponse.from(page, cursors)));
  }
  private static <T> Mono<T> blocking(Callable<T> action) { return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic()); }
  public record SearchPageResponse(List<SearchResponse> items, String nextCursor) { static SearchPageResponse from(SearchResultPage page, SearchCursorCodec codec) { return new SearchPageResponse(page.items().stream().map(SearchResponse::from).toList(), page.nextCursor().map(codec::encode).orElse(null)); } }
  public record SearchResponse(String objectType, String objectId, String projectId, String title, String subtitle, String status, String updatedAt, String route, String snippet) { static SearchResponse from(SearchResultItem i) { return new SearchResponse(i.objectType().name(), i.objectId().toString(), i.projectId().map(Object::toString).orElse(null), i.title(), i.subtitle().orElse(null), i.status(), i.updatedAt().toString(), i.route(), i.snippet().orElse(null)); } }
}
