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
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Current-membership HTTP and SSE boundary for Team Activity. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/activity")
public final class TeamActivityController {

  private static final ProjectionName PROJECTION =
      new ProjectionName(ActivityApiSupport.PROJECTION_NAME);

  private final ActivityApplicationService service;
  private final TeamRequestIdentityResolver identityResolver;
  private final ObjectProvider<TeamActivityCursorCodec> cursorCodecs;
  private final ObjectProvider<TeamActivityRealtimeStream> realtimeStreams;

  public TeamActivityController(
      ActivityApplicationService service,
      TeamRequestIdentityResolver identityResolver,
      ObjectProvider<TeamActivityCursorCodec> cursorCodecs,
      ObjectProvider<TeamActivityRealtimeStream> realtimeStreams) {
    this.service = service;
    this.identityResolver = identityResolver;
    this.cursorCodecs = cursorCodecs;
    this.realtimeStreams = realtimeStreams;
  }

  @GetMapping
  public Mono<ResponseEntity<ActivityPageResponse>> history(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestParam(required = false) String workItemId,
      @RequestParam(required = false) List<String> categories,
      @RequestParam(required = false) List<String> eventTypes,
      @RequestParam(required = false) List<String> actorPrincipalIds,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    ActivityApiSupport.TeamRoute route = ActivityApiSupport.teamRoute(organizationId, teamId);
    ActivityFilter filter = ActivityApiSupport.teamFilter(
        workItemId, categories, eventTypes, actorPrincipalIds);
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
      @RequestParam(required = false) String workItemId,
      @RequestParam(required = false) List<String> categories,
      @RequestParam(required = false) List<String> eventTypes,
      @RequestParam(required = false) List<String> actorPrincipalIds,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    ActivityApiSupport.TeamRoute route = ActivityApiSupport.teamRoute(organizationId, teamId);
    ActivityFilter filter = ActivityApiSupport.teamFilter(
        workItemId, categories, eventTypes, actorPrincipalIds);
    TeamActivityCursorCodec codec = cursorCodec();
    int pageSize = ApiPagination.limit(limit);
    return resolve(authentication, route, exchange)
        .flatMap(access -> blocking(() -> service.teamSnapshot(
            access, snapshotRequest(route, filter, pageSize))))
        .map(value -> noStore(ActivitySnapshotResponse.from(value, codec)));
  }

  @GetMapping("/{activityEventId}")
  public Mono<ResponseEntity<ActivityResponse>> detail(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String activityEventId,
      Authentication authentication,
      ServerWebExchange exchange) {
    ActivityApiSupport.TeamRoute route = ActivityApiSupport.teamRoute(organizationId, teamId);
    ActivityEventId eventId = eventId(activityEventId);
    return resolve(authentication, route, exchange)
        .flatMap(access -> blocking(() -> service.teamDetail(
            access, route.organizationId(), route.teamId(), eventId)))
        .map(value -> noStore(ActivityResponse.from(value)));
  }

  @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Mono<ResponseEntity<Flux<ServerSentEvent<ActivityResponse>>>> events(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestParam(required = false) String workItemId,
      @RequestParam(required = false) List<String> categories,
      @RequestParam(required = false) List<String> eventTypes,
      @RequestParam(required = false) List<String> actorPrincipalIds,
      @RequestHeader(name = ApiHeaders.LAST_EVENT_ID, required = false) String lastEventId,
      @RequestParam(required = false) String after,
      Authentication authentication,
      ServerWebExchange exchange) {
    ActivityApiSupport.TeamRoute route = ActivityApiSupport.teamRoute(organizationId, teamId);
    ActivityFilter filter = ActivityApiSupport.teamFilter(
        workItemId, categories, eventTypes, actorPrincipalIds);
    String resumeToken = ActivityApiSupport.resumeToken(lastEventId, after);
    TeamActivityRealtimeStream stream = realtimeStream();
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return identityResolver.resolve(authentication, route.organizationId(), correlationId)
        // Membership and the first durable read both complete before HTTP 200 is committed.
        .flatMap(access -> blocking(() -> {
          service.requireTeamAccess(access, route.organizationId(), route.teamId());
          return access;
        }))
        .flatMap(access -> stream.open(
                streamRequest(route, filter),
                Optional.ofNullable(resumeToken).filter(value -> !value.isBlank()))
            .map(session -> sseResponse(access, route, session)));
  }

  private AuthorizedActivityPage history(
      TeamAccessContext access,
      ActivityApiSupport.TeamRoute route,
      ActivityFilter filter,
      String after,
      int limit,
      TeamActivityCursorCodec codec) {
    if (after == null || after.isBlank()) {
      AuthorizedActivitySnapshot snapshot = service.teamSnapshot(
          access, snapshotRequest(route, filter, limit));
      return new AuthorizedActivityPage(
          snapshot.events(), snapshot.hasMore(), snapshot.nextCursor());
    }
    // Membership is checked before decoding a scope-bearing token to avoid a cursor oracle.
    service.requireTeamAccess(access, route.organizationId(), route.teamId());
    TeamActivityCursor cursor = codec.decode(
        after, route.organizationId(), route.teamId(), PROJECTION, filter);
    return service.teamHistory(
        access, new ActivityQuery(cursor.scope(), filter, Optional.of(cursor), limit));
  }

  private ResponseEntity<Flux<ServerSentEvent<ActivityResponse>>> sseResponse(
      TeamAccessContext access,
      ActivityApiSupport.TeamRoute route,
      TeamActivitySseSession session) {
    Flux<ServerSentEvent<ActivityResponse>> body = session.body()
        .concatMap(frame -> authorizeFrame(access, route, frame), 1);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  private Mono<ServerSentEvent<ActivityResponse>> authorizeFrame(
      TeamAccessContext access,
      ActivityApiSupport.TeamRoute route,
      ServerSentEvent<TeamActivityStreamEvent> frame) {
    return blocking(() -> {
      TeamActivityStreamEvent data = frame.data();
      if (data == null) {
        service.requireTeamAccess(access, route.organizationId(), route.teamId());
        return Optional.of(copyFrame(frame, null));
      }
      if (!service.canViewNow(
          access, route.organizationId(), route.teamId(), data.activity())) {
        return Optional.<ServerSentEvent<ActivityResponse>>empty();
      }
      return Optional.of(copyFrame(frame, ActivityResponse.from(data.activity())));
    }).flatMap(Mono::justOrEmpty);
  }

  private static ServerSentEvent<ActivityResponse> copyFrame(
      ServerSentEvent<TeamActivityStreamEvent> source, ActivityResponse data) {
    ServerSentEvent.Builder<ActivityResponse> builder = ServerSentEvent.builder(data);
    if (source.id() != null) {
      builder.id(source.id());
    }
    if (source.event() != null) {
      builder.event(source.event());
    }
    if (source.comment() != null) {
      builder.comment(source.comment());
    }
    if (source.retry() != null) {
      builder.retry(source.retry());
    }
    return builder.build();
  }

  private Mono<TeamAccessContext> resolve(
      Authentication authentication,
      ActivityApiSupport.TeamRoute route,
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

  private TeamActivityRealtimeStream realtimeStream() {
    TeamActivityRealtimeStream stream = realtimeStreams.getIfAvailable();
    if (stream == null) {
      throw ActivityApiSupport.unavailable();
    }
    return stream;
  }

  private static TeamActivitySnapshotRequest snapshotRequest(
      ActivityApiSupport.TeamRoute route, ActivityFilter filter, int limit) {
    return new TeamActivitySnapshotRequest(
        route.organizationId(), route.teamId(), PROJECTION, filter, limit);
  }

  private static TeamActivityStreamRequest streamRequest(
      ActivityApiSupport.TeamRoute route, ActivityFilter filter) {
    return new TeamActivityStreamRequest(
        route.organizationId(), route.teamId(), PROJECTION, filter);
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
