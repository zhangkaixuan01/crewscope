package io.crewscope.server.api;

import io.crewscope.application.workitem.WorkItemAvailableTransition;
import io.crewscope.application.workitem.WorkItemTransitionAvailabilityQuery;
import io.crewscope.application.workitem.WorkItemTransitionAvailabilityQueryService;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** HTTP boundary for runtime WorkItem transition discoverability. */
@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/transitions")
public final class WorkItemTransitionController {
  private final WorkItemTransitionAvailabilityQueryService service;
  private final TeamRequestIdentityResolver identityResolver;

  public WorkItemTransitionController(
      WorkItemTransitionAvailabilityQueryService service,
      TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @GetMapping("/availability")
  public Mono<ResponseEntity<WorkItemTransitionAvailabilityResponse>> availability(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = parse(organizationId, OrganizationId::from, "organizationId");
    TeamId team = parse(teamId, TeamId::from, "teamId");
    WorkProjectId project = parse(projectId, WorkProjectId::from, "projectId");
    WorkItemId item = parse(workItemId, WorkItemId::from, "workItemId");
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return identityResolver.resolve(authentication, organization, correlationId)
        .flatMap(access -> blocking(() -> service.list(new WorkItemTransitionAvailabilityQuery(
            access, organization, team, project, item))))
        .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(WorkItemTransitionAvailabilityResponse.from(value)));
  }

  private static <T> T parse(
      String value, java.util.function.Function<String, T> parser, String field) {
    try {
      return parser.apply(value);
    } catch (IllegalArgumentException failure) {
      throw new ApiRequestException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Request contains an invalid identifier",
          Map.of("field", field));
    }
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  public record WorkItemTransitionAvailabilityResponse(
      List<AvailableActionResponse> transitions) {
    static WorkItemTransitionAvailabilityResponse from(List<WorkItemAvailableTransition> value) {
      return new WorkItemTransitionAvailabilityResponse(
          value.stream().map(WorkItemTransitionController::response).toList());
    }
  }

  /**
   * Maps one adjudicated edge onto the shared wire shape.
   *
   * <p>Public because every surface that carries WorkItem edges — this endpoint, a WorkItem list row
   * and the personal WorkDesk — must produce the identical object, and a per-surface mapping would be
   * the place where they start to differ.
   */
  public static AvailableActionResponse response(WorkItemAvailableTransition value) {
    return AvailableActionResponse.of(
        value.actionId(),
        value.targetStatus().name(),
        value.label(),
        value.strength().name(),
        value.reversible(),
        value.enabled(),
        value.reason().orElse(null),
        value.remedy().orElse(null));
  }
}
