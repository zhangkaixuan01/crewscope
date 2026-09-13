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
      List<WorkItemAvailableTransitionResponse> transitions) {
    static WorkItemTransitionAvailabilityResponse from(List<WorkItemAvailableTransition> value) {
      return new WorkItemTransitionAvailabilityResponse(
          value.stream().map(WorkItemAvailableTransitionResponse::from).toList());
    }
  }

  public record WorkItemAvailableTransitionResponse(
      String actionId,
      String targetStatus,
      String label,
      String strength,
      boolean reversible,
      boolean enabled,
      String reason,
      String reasonMessage,
      String remedyLabel,
      String remedyRoute) {
    static WorkItemAvailableTransitionResponse from(WorkItemAvailableTransition value) {
      return new WorkItemAvailableTransitionResponse(
          value.actionId(), value.targetStatus().name(), value.label(), value.strength().name(),
          value.reversible(), value.enabled(), value.reason().map(Enum::name).orElse(null),
          value.reason().map(reason -> reason.message()).orElse(null),
          value.remedy().map(remedy -> remedy.label()).orElse(null),
          value.remedy().map(remedy -> remedy.route()).orElse(null));
    }
  }
}
