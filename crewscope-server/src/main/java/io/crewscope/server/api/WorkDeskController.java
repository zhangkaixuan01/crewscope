package io.crewscope.server.api;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workdesk.WorkDeskItem;
import io.crewscope.application.workdesk.WorkDeskQueryService;
import io.crewscope.application.workdesk.WorkDeskSection;
import io.crewscope.application.workdesk.WorkDeskSummary;
import java.util.List;
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

/** Member-facing HTTP boundary for the derived personal WorkDesk summary. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk")
public final class WorkDeskController {
  private final WorkDeskQueryService service;
  private final TeamRequestIdentityResolver identityResolver;

  public WorkDeskController(WorkDeskQueryService service, TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @GetMapping
  public Mono<ResponseEntity<WorkDeskSummaryResponse>> get(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String responsibilityRole,
      @RequestParam(defaultValue = "false") boolean onlyNeedsAction,
      Authentication authentication,
      ServerWebExchange exchange) {
    WorkDeskApiSupport.Route route = WorkDeskApiSupport.route(organizationId, teamId);
    var project = WorkDeskApiSupport.project(projectId);
    var role = WorkDeskApiSupport.role(responsibilityRole);
    return identityResolver.resolve(authentication, route.organizationId(), ApiCorrelationIds.resolve(exchange))
        .flatMap(access -> blocking(() -> service.summarize(
            access, route.organizationId(), route.teamId(), project, role, onlyNeedsAction)))
        .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(WorkDeskSummaryResponse.from(value)));
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  public record WorkDeskSummaryResponse(
      String organizationId, String teamId, String projectId, String generatedAt,
      List<WorkDeskSectionResponse> sections) {
    static WorkDeskSummaryResponse from(WorkDeskSummary value) {
      return new WorkDeskSummaryResponse(value.organizationId(), value.teamId(),
          value.projectId().orElse(null), value.generatedAt().toString(),
          value.sections().stream().map(WorkDeskSectionResponse::from).toList());
    }
  }

  public record WorkDeskSectionResponse(
      String key, String title, int priority, int total, boolean truncated,
      List<WorkDeskItemResponse> items) {
    static WorkDeskSectionResponse from(WorkDeskSection value) {
      return new WorkDeskSectionResponse(value.key(), value.title(), value.priority(), value.total(),
          value.truncated(), value.items().stream().map(WorkDeskItemResponse::from).toList());
    }
  }

  public record WorkDeskItemResponse(
      String objectType, String objectId, String projectId, String title, String status,
      String updatedAt, String responsibilityRole, boolean needsAction, String urgency,
      Integer progress, List<String> availableActions, String route) {
    static WorkDeskItemResponse from(WorkDeskItem value) {
      return new WorkDeskItemResponse(value.objectType(), value.objectId(), value.projectId().orElse(null),
          value.title().orElse(null), value.status(), value.updatedAt().toString(),
          value.responsibilityRole().orElse(null), value.needsAction(), value.urgency(),
          value.progress().orElse(null), value.availableActions(), value.route());
    }
  }
}
