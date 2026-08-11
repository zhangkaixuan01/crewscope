package io.crewscope.server.api;

import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationEvent;
import io.crewscope.application.conversation.ConversationEventCursor;
import io.crewscope.application.conversation.ConversationEventPage;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.server.config.application.ConversationEventStreamProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
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

/** Visibility-aware JSON and resumable SSE boundary for durable Conversation events. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/conversations")
public final class ConversationEventController {

  private final ConversationApplicationService service;
  private final TeamRequestIdentityResolver identityResolver;
  private final ConversationEventStreamProperties properties;
  private final ConversationEventCursorCodec cursorCodec = new ConversationEventCursorCodec();

  public ConversationEventController(
      ConversationApplicationService service,
      TeamRequestIdentityResolver identityResolver,
      ConversationEventStreamProperties properties) {
    this.service = service;
    this.identityResolver = identityResolver;
    this.properties = properties;
  }

  @GetMapping(path = "/{conversationId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<ResponseEntity<ConversationEventPageResponse>> history(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    StreamRoute route = route(organizationId, teamId, conversationId);
    Optional<ConversationEventCursor> cursor = decode(after, route);
    return resolve(authentication, route.organizationId(), exchange)
        .flatMap(
            access ->
                page(access, route, cursor, ApiPagination.limit(limit)))
        .map(
            result ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(ConversationEventPageResponse.from(result, cursorCodec)));
  }

  @GetMapping(path = "/{conversationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Mono<ResponseEntity<Flux<ServerSentEvent<RealtimeEventResponse<Map<String, Object>>>>>>
      stream(
          @PathVariable String organizationId,
          @PathVariable String teamId,
          @PathVariable String conversationId,
          @RequestHeader(name = ApiHeaders.LAST_EVENT_ID, required = false) String lastEventId,
          @RequestParam(required = false) String after,
          Authentication authentication,
          ServerWebExchange exchange) {
    StreamRoute route = route(organizationId, teamId, conversationId);
    Optional<ConversationEventCursor> cursor = decode(resolveResumeToken(lastEventId, after), route);
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return resolve(authentication, route.organizationId(), correlationId)
        // Resolve visibility and the initial durable page before committing the 200 response.
        .flatMap(access -> page(access, route, cursor, properties.getBatchSize())
            .map(initial -> sseResponse(authentication, correlationId, route, cursor, initial)));
  }

  private ResponseEntity<Flux<ServerSentEvent<RealtimeEventResponse<Map<String, Object>>>>>
      sseResponse(
          Authentication authentication,
          UUID correlationId,
          StreamRoute route,
          Optional<ConversationEventCursor> requestedCursor,
          ConversationEventPage initialPage) {
    AtomicReference<ConversationEventCursor> position =
        new AtomicReference<>(
            initialPage.nextCursor().orElse(requestedCursor.orElse(null)));
    Flux<ConversationEvent> initial =
        Flux.fromIterable(initialPage.events()).doOnNext(event -> position.set(event.cursor()));
    Flux<ConversationEvent> updates =
        Flux.interval(properties.getPollInterval())
            // A slow subscriber may coalesce empty polling opportunities, never event rows.
            .onBackpressureDrop()
            .concatMap(
                ignored ->
                    resolve(authentication, route.organizationId(), correlationId)
                        .flatMap(
                            access ->
                                page(
                                    access,
                                    route,
                                    Optional.ofNullable(position.get()),
                                    properties.getBatchSize())),
                1)
            .concatMapIterable(ConversationEventPage::events)
            .doOnNext(event -> position.set(event.cursor()));
    Flux<ServerSentEvent<RealtimeEventResponse<Map<String, Object>>>> body =
        Flux.concat(initial, updates)
            .map(
                event ->
                    ServerSentEvent.<RealtimeEventResponse<Map<String, Object>>>builder(
                            RealtimeEventResponse.from(event.envelope()))
                        .id(cursorCodec.encode(event.cursor()))
                        .event(event.envelope().eventType().value())
                        .build());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  private Mono<ConversationEventPage> page(
      TeamAccessContext access,
      StreamRoute route,
      Optional<ConversationEventCursor> cursor,
      int limit) {
    return blocking(
        () ->
            service.events(
                access,
                route.organizationId(),
                route.teamId(),
                route.conversationId(),
                cursor,
                limit));
  }

  private Mono<TeamAccessContext> resolve(
      Authentication authentication,
      OrganizationId organizationId,
      ServerWebExchange exchange) {
    return resolve(authentication, organizationId, ApiCorrelationIds.resolve(exchange));
  }

  private Mono<TeamAccessContext> resolve(
      Authentication authentication, OrganizationId organizationId, UUID correlationId) {
    return identityResolver.resolve(authentication, organizationId, correlationId);
  }

  private Optional<ConversationEventCursor> decode(String token, StreamRoute route) {
    return Optional.ofNullable(token)
        .filter(value -> !value.isBlank())
        .map(
            value ->
                cursorCodec.decode(
                    value,
                    route.organizationId(),
                    route.teamId(),
                    route.conversationId()));
  }

  private static String resolveResumeToken(String lastEventId, String after) {
    boolean hasHeader = lastEventId != null && !lastEventId.isBlank();
    boolean hasParameter = after != null && !after.isBlank();
    if (hasHeader && hasParameter && !lastEventId.equals(after)) {
      throw new ApiRequestException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "invalid_cursor",
          "Last-Event-ID and after must identify the same position",
          Map.of("header", ApiHeaders.LAST_EVENT_ID, "parameter", "after"));
    }
    return hasHeader ? lastEventId : after;
  }

  private static StreamRoute route(
      String organizationId, String teamId, String conversationId) {
    try {
      return new StreamRoute(
          OrganizationId.from(organizationId),
          TeamId.from(teamId),
          ConversationId.from(conversationId));
    } catch (IllegalArgumentException exception) {
      throw new ApiRequestException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Request contains an invalid identifier",
          Map.of("route", "conversation-events"));
    }
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  private record StreamRoute(
      OrganizationId organizationId, TeamId teamId, ConversationId conversationId) {}

  public record ConversationEventPageResponse(
      List<ConversationEventResponse> items, boolean hasMore, String nextCursor) {

    static ConversationEventPageResponse from(
        ConversationEventPage page, ConversationEventCursorCodec codec) {
      return new ConversationEventPageResponse(
          page.events().stream()
              .map(event -> ConversationEventResponse.from(event, codec))
              .toList(),
          page.hasMore(),
          page.nextCursor().map(codec::encode).orElse(null));
    }
  }

  public record ConversationEventResponse(
      String cursor, RealtimeEventResponse<Map<String, Object>> event) {

    static ConversationEventResponse from(
        ConversationEvent event, ConversationEventCursorCodec codec) {
      return new ConversationEventResponse(
          codec.encode(event.cursor()), RealtimeEventResponse.from(event.envelope()));
    }
  }
}
