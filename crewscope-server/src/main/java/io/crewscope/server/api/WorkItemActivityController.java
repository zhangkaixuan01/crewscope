package io.crewscope.server.api;

import io.crewscope.application.activity.ActivityApplicationService;
import io.crewscope.application.activity.ActivityFilter;
import io.crewscope.application.activity.ActivityQuery;
import io.crewscope.application.activity.AuthorizedActivityPage;
import io.crewscope.application.activity.AuthorizedActivitySnapshot;
import io.crewscope.application.activity.TeamActivityCursor;
import io.crewscope.application.activity.TeamActivitySnapshotRequest;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.activity.ActivityEventId;
import io.crewscope.domain.projection.ProjectionName;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.ObjectProvider;
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

/** Full-scope HTTP boundary for WorkItem-filtered Activity. */
@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}"
        + "/work-items/{workItemId}/activity")
public final class WorkItemActivityController {

  private static final ProjectionName PROJECTION =
      new ProjectionName(ActivityApiSupport.PROJECTION_NAME);

  private final ActivityApplicationService service;
  private final TeamRequestIdentityResolver identityResolver;
  private final ObjectProvider<TeamActivityCursorCodec> cursorCodecs;

  public WorkItemActivityController(
      ActivityApplicationService service,
      TeamRequestIdentityResolver identityResolver,
      ObjectProvider<TeamActivityCursorCodec> cursorCodecs) {
    this.service = service;
    this.identityResolver = identityResolver;
    this.cursorCodecs = cursorCodecs;
  }

  @GetMapping
  public Mono<ResponseEntity<ActivityPageResponse>> history(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @RequestParam(required = false) List<String> categories,
      @RequestParam(required = false) List<String> eventTypes,
      @RequestParam(required = false) List<String> actorPrincipalIds,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    ActivityApiSupport.WorkItemRoute route = ActivityApiSupport.workItemRoute(
        organizationId, teamId, projectId, workItemId);
    ActivityFilter filter = ActivityApiSupport.workItemFilter(
        route.workItemId(), categories, eventTypes, actorPrincipalIds);
    TeamActivityCursorCodec codec = cursorCodec();
    int pageSize = ApiPagination.limit(limit);
    return resolve(authentication, route, exchange)
        .flatMap(access -> blocking(() -> history(access, route, filter, after, pageSize, codec)))
        .map(page -> noStore(ActivityPageResponse.from(page, codec)));
  }

  @GetMapping("/snapshot")
  public Mono<ResponseEntity<ActivitySnapshotResponse>> snapshot(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @RequestParam(required = false) List<String> categories,
      @RequestParam(required = false) List<String> eventTypes,
      @RequestParam(required = false) List<String> actorPrincipalIds,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    ActivityApiSupport.WorkItemRoute route = ActivityApiSupport.workItemRoute(
        organizationId, teamId, projectId, workItemId);
    ActivityFilter filter = ActivityApiSupport.workItemFilter(
        route.workItemId(), categories, eventTypes, actorPrincipalIds);
    TeamActivityCursorCodec codec = cursorCodec();
    int pageSize = ApiPagination.limit(limit);
    return resolve(authentication, route, exchange)
        .flatMap(access -> blocking(() -> service.workItemSnapshot(
            access,
            route.projectId(),
            route.workItemId(),
            snapshotRequest(route, filter, pageSize))))
        .map(value -> noStore(ActivitySnapshotResponse.from(value, codec)));
  }

  @GetMapping("/{activityEventId}")
  public Mono<ResponseEntity<ActivityResponse>> detail(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @PathVariable String activityEventId,
      Authentication authentication,
      ServerWebExchange exchange) {
    ActivityApiSupport.WorkItemRoute route = ActivityApiSupport.workItemRoute(
        organizationId, teamId, projectId, workItemId);
    ActivityEventId eventId = eventId(activityEventId);
    return resolve(authentication, route, exchange)
        .flatMap(access -> blocking(() -> service.workItemDetail(
            access,
            route.organizationId(),
            route.teamId(),
            route.projectId(),
            route.workItemId(),
            eventId)))
        .map(value -> noStore(ActivityResponse.from(value)));
  }

  private AuthorizedActivityPage history(
      TeamAccessContext access,
      ActivityApiSupport.WorkItemRoute route,
      ActivityFilter filter,
      String after,
      int limit,
      TeamActivityCursorCodec codec) {
    if (after == null || after.isBlank()) {
      AuthorizedActivitySnapshot snapshot = service.workItemSnapshot(
          access,
          route.projectId(),
          route.workItemId(),
          snapshotRequest(route, filter, limit));
      return new AuthorizedActivityPage(
          snapshot.events(), snapshot.hasMore(), snapshot.nextCursor());
    }
    // The exact WorkItem route is authorized before decoding a scope-bearing token.
    service.requireWorkItemAccess(
        access,
        route.organizationId(),
        route.teamId(),
        route.projectId(),
        route.workItemId());
    TeamActivityCursor cursor = codec.decode(
        after, route.organizationId(), route.teamId(), PROJECTION, filter);
    return service.workItemHistory(
        access,
        route.projectId(),
        route.workItemId(),
        new ActivityQuery(cursor.scope(), filter, Optional.of(cursor), limit));
  }

  private Mono<TeamAccessContext> resolve(
      Authentication authentication,
      ActivityApiSupport.WorkItemRoute route,
      ServerWebExchange exchange) {
    return identityResolver.resolve(
        authentication, route.organizationId(), ApiCorrelationIds.resolve(exchange));
  }

  private TeamActivityCursorCodec cursorCodec() {
    TeamActivityCursorCodec codec = cursorCodecs.getIfAvailable();
    if (codec == null) {
      throw ActivityApiSupport.unavailable();
    }
    return codec;
  }

  private static TeamActivitySnapshotRequest snapshotRequest(
      ActivityApiSupport.WorkItemRoute route, ActivityFilter filter, int limit) {
    return new TeamActivitySnapshotRequest(
        route.organizationId(), route.teamId(), PROJECTION, filter, limit);
  }

  private static ActivityEventId eventId(String value) {
    try {
      return ActivityEventId.from(value);
    } catch (IllegalArgumentException failure) {
      throw new ApiRequestException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Request contains an invalid Activity Event identifier",
          Map.of("field", "activityEventId"));
    }
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  private static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
