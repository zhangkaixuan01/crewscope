package io.crewscope.server.api;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemTimelineEvent;
import io.crewscope.application.workitem.WorkItemTimelinePage;
import io.crewscope.application.workitem.WorkItemTimelineService;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HTTP boundary for the cursor-paginated WorkItem business timeline. */
@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/timeline")
public final class WorkItemTimelineController {

  private final WorkItemTimelineService timelineService;
  private final TeamRequestIdentityResolver identityResolver;
  private final ObjectMapper objectMapper;
  private final WorkItemTimelineCursorCodec cursorCodec = new WorkItemTimelineCursorCodec();

  public WorkItemTimelineController(
      WorkItemTimelineService timelineService,
      TeamRequestIdentityResolver identityResolver,
      ObjectMapper objectMapper) {
    this.timelineService = timelineService;
    this.identityResolver = identityResolver;
    this.objectMapper = objectMapper;
  }

  @GetMapping
  public Mono<ResponseEntity<WorkItemTimelinePageResponse>> list(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    WorkItemId item = workItemId(workItemId);
    var cursor = Optional.ofNullable(after).map(cursorCodec::decode);
    int pageSize = ApiPagination.limit(limit);
    return query(
            authentication,
            organization,
            exchange,
            access ->
                timelineService.list(
                    access, organization, team, project, item, cursor, pageSize))
        .map(
            page ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(WorkItemTimelinePageResponse.from(page, cursorCodec, objectMapper)));
  }

  private <T> Mono<T> query(
      Authentication authentication,
      OrganizationId organizationId,
      ServerWebExchange exchange,
      Function<TeamAccessContext, T> action) {
    return identityResolver
        .resolve(authentication, organizationId, ApiCorrelationIds.resolve(exchange))
        .flatMap(access -> blocking(() -> action.apply(access)));
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  private static OrganizationId organizationId(String value) {
    try {
      return OrganizationId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("organizationId");
    }
  }

  private static TeamId teamId(String value) {
    try {
      return TeamId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("teamId");
    }
  }

  private static WorkProjectId projectId(String value) {
    try {
      return WorkProjectId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("projectId");
    }
  }

  private static WorkItemId workItemId(String value) {
    try {
      return WorkItemId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("workItemId");
    }
  }

  private static ApiRequestException invalidIdentifier(String field) {
    return new ApiRequestException(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Request contains an invalid identifier",
        Map.of("field", field));
  }

  public record WorkItemTimelinePageResponse(
      List<WorkItemTimelineEventResponse> items, String nextCursor) {

    static WorkItemTimelinePageResponse from(
        WorkItemTimelinePage page,
        WorkItemTimelineCursorCodec cursorCodec,
        ObjectMapper objectMapper) {
      return new WorkItemTimelinePageResponse(
          page.items().stream()
              .map(event -> WorkItemTimelineEventResponse.from(event, objectMapper))
              .toList(),
          page.nextCursor().map(cursorCodec::encode).orElse(null));
    }
  }

  public record WorkItemTimelineEventResponse(
      String eventId,
      String domainEventId,
      String source,
      String eventType,
      String schemaVersion,
      String aggregateType,
      String aggregateId,
      Long aggregateVersion,
      String actorType,
      String actorPrincipalId,
      String actorDisplayName,
      String correlationId,
      String causationId,
      String occurredAt,
      String outcome,
      JsonNode payload) {

    static WorkItemTimelineEventResponse from(
        WorkItemTimelineEvent event, ObjectMapper objectMapper) {
      JsonNode payload = objectMapper.readTree(event.payloadJson());
      if (payload == null || !payload.isObject()) {
        throw new IllegalStateException("Persisted timeline payload must be a JSON object");
      }
      return new WorkItemTimelineEventResponse(
          event.eventId().toString(),
          event.domainEventId().map(Object::toString).orElse(null),
          event.source().name(),
          event.eventType(),
          event.schemaVersion(),
          event.aggregateType(),
          event.aggregateId().toString(),
          event.aggregateVersion().orElse(null),
          event.actorType().name(),
          event.actorPrincipalId().map(Object::toString).orElse(null),
          event.actorDisplayName().orElse(null),
          event.correlationId().toString(),
          event.causationId().map(Object::toString).orElse(null),
          event.occurredAt().toString(),
          event.outcome(),
          payload);
    }
  }
}
