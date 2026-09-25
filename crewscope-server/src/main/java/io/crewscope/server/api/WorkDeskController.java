package io.crewscope.server.api;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workdesk.WorkDeskItem;
import io.crewscope.application.workdesk.WorkDeskQueryService;
import io.crewscope.application.workdesk.WorkDeskSection;
import io.crewscope.application.workdesk.WorkDeskSectionPosition;
import io.crewscope.application.workdesk.WorkDeskSummary;
import io.crewscope.application.workdesk.WorkDeskWaitingOn;
import io.crewscope.application.workitem.WorkItemExecutionSummary;
import io.crewscope.application.workitem.WorkItemFilterFingerprint;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
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

/**
 * Member-facing HTTP boundary for the derived personal WorkDesk summary.
 *
 * <p>M9b-A06: the first screen pages every section on its own bounded limit and hands each
 * truncated section a signed continuation cursor; {@code GET /sections/{sectionKey}} continues
 * exactly one section from that cursor. A cursor only replays on the member, section and filter
 * that produced it, so a changed filter is a new query, not a corrupted continuation.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk")
public final class WorkDeskController {
  private final WorkDeskQueryService service;
  private final WorkDeskSectionCursorCodec cursorCodec;
  private final TeamRequestIdentityResolver identityResolver;

  public WorkDeskController(
      WorkDeskQueryService service,
      WorkDeskSectionCursorCodec cursorCodec,
      TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.cursorCodec = cursorCodec;
    this.identityResolver = identityResolver;
  }

  @GetMapping
  public Mono<ResponseEntity<WorkDeskSummaryResponse>> get(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String responsibilityRole,
      @RequestParam(defaultValue = "false") boolean onlyNeedsAction,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    WorkDeskApiSupport.Route route = WorkDeskApiSupport.route(organizationId, teamId);
    var project = WorkDeskApiSupport.project(projectId);
    var role = WorkDeskApiSupport.role(responsibilityRole);
    int sectionLimit = ApiPagination.workDeskSectionLimit(limit);
    return identityResolver.resolve(authentication, route.organizationId(), ApiCorrelationIds.resolve(exchange))
        .flatMap(access -> blocking(() -> WorkDeskSummaryResponse.from(
            service.summarize(
                access, route.organizationId(), route.teamId(), project, role, onlyNeedsAction,
                sectionLimit),
            cursorCodec, cursorFacts(access, route, project, role, onlyNeedsAction))))
        .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value));
  }

  @GetMapping("/sections/{sectionKey}")
  public Mono<ResponseEntity<WorkDeskSectionResponse>> section(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String sectionKey,
      @RequestParam String after,
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String responsibilityRole,
      @RequestParam(defaultValue = "false") boolean onlyNeedsAction,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    WorkDeskApiSupport.Route route = WorkDeskApiSupport.route(organizationId, teamId);
    String section = WorkDeskApiSupport.sectionKey(sectionKey);
    var project = WorkDeskApiSupport.project(projectId);
    var role = WorkDeskApiSupport.role(responsibilityRole);
    int sectionLimit = ApiPagination.workDeskSectionLimit(limit);
    return identityResolver.resolve(authentication, route.organizationId(), ApiCorrelationIds.resolve(exchange))
        .flatMap(access -> blocking(() -> {
          CursorFacts facts = cursorFacts(access, route, project, role, onlyNeedsAction);
          WorkDeskSectionPosition position = cursorCodec
              .decode(after, facts.scope(section)).position();
          return WorkDeskSectionResponse.from(
              service.summarizeSection(
                  access, route.organizationId(), route.teamId(), project, role, onlyNeedsAction,
                  sectionLimit, section, position),
              cursorCodec, facts);
        }))
        .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value));
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  /** The parts of a cursor scope shared by every section of one screen: tenant, viewer, filter. */
  private static CursorFacts cursorFacts(
      TeamAccessContext access,
      WorkDeskApiSupport.Route route,
      Optional<WorkProjectId> project,
      Optional<ResponsibilityRole> role,
      boolean onlyNeedsAction) {
    return new CursorFacts(
        route.organizationId(),
        route.teamId(),
        access.actor().id(),
        WorkDeskApiSupport.fingerprint(project, role, onlyNeedsAction));
  }

  private record CursorFacts(
      OrganizationId organizationId,
      TeamId teamId,
      PrincipalId viewerPrincipalId,
      WorkItemFilterFingerprint fingerprint) {

    WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope scope(String sectionKey) {
      return new WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope(
          organizationId, teamId, viewerPrincipalId, sectionKey, fingerprint);
    }
  }

  public record WorkDeskSummaryResponse(
      String organizationId, String teamId, String projectId, String generatedAt,
      List<WorkDeskSectionResponse> sections) {
    static WorkDeskSummaryResponse from(
        WorkDeskSummary value, WorkDeskSectionCursorCodec codec, CursorFacts facts) {
      return new WorkDeskSummaryResponse(value.organizationId(), value.teamId(),
          value.projectId().orElse(null), value.generatedAt().toString(),
          value.sections().stream()
              .map(section -> WorkDeskSectionResponse.from(section, codec, facts)).toList());
    }
  }

  public record WorkDeskSectionResponse(
      String key, String title, int priority, int total, boolean truncated,
      List<WorkDeskItemResponse> items, String nextCursor) {
    static WorkDeskSectionResponse from(
        WorkDeskSection value, WorkDeskSectionCursorCodec codec, CursorFacts facts) {
      return new WorkDeskSectionResponse(value.key(), value.title(), value.priority(), value.total(),
          value.truncated(),
          value.items().stream().map(WorkDeskItemResponse::from).toList(),
          value.nextPosition()
              .map(position -> codec.encode(
                  new WorkDeskSectionCursorCodec.WorkDeskSectionCursor(
                      facts.scope(value.key()), position)))
              .orElse(null));
    }
  }

  public record WorkDeskItemResponse(
      String objectType, String objectId, String projectId, String title, String status,
      String updatedAt, String responsibilityRole, boolean needsAction, String urgency,
      Integer progress, List<AvailableActionResponse> availableActions,
      String route, String workItemId, String workItemTitle,
      WorkDeskRowSummaryResponse rowSummary, WaitingOnResponse waitingOn) {
    static WorkDeskItemResponse from(WorkDeskItem value) {
      return new WorkDeskItemResponse(value.objectType(), value.objectId(), value.projectId().orElse(null),
          value.title().orElse(null), value.status(), value.updatedAt().toString(),
          value.responsibilityRole().orElse(null), value.needsAction(), value.urgency(),
          value.progress().orElse(null),
          // The transition DTO is reused, not re-declared, so the WorkDesk and the per-object
          // availability endpoint cannot serialize the same action differently.
          value.availableActions().stream()
              .map(WorkItemTransitionController::response)
              .toList(),
          value.route(),
          value.workItemId().orElse(null), value.workItemTitle().orElse(null),
          value.rowSummary().map(WorkDeskRowSummaryResponse::from).orElse(null),
          value.waitingOn().map(WaitingOnResponse::from).orElse(null));
    }
  }

  /**
   * The §4.1 minimal facts one desk row stands for: how much work it represents and what it waits
   * on. {@code waitingReason} is the first blocked reason's code — the person behind it, when there
   * is exactly one, arrives separately as {@code waitingOn} for the surface to resolve against its
   * own member list.
   */
  public record WorkDeskRowSummaryResponse(
      int taskCount, int activeTaskCount, int pendingReviewCount, boolean selectionRequired,
      String currentExecutionStatus, String waitingReason, String observedAt, long workItemVersion) {
    static WorkDeskRowSummaryResponse from(WorkItemExecutionSummary summary) {
      return new WorkDeskRowSummaryResponse(
          summary.taskCount(), summary.activeTaskCount(), summary.pendingReviewCount(),
          summary.selectionRequired(),
          summary.executionStatus().map(Enum::name).orElse(null),
          summary.blockedReasons().isEmpty()
              ? null : summary.blockedReasons().get(0).code().name(),
          summary.observedAt().toString(),
          summary.workItemVersion());
    }
  }

  public record WaitingOnResponse(String principalId, String displayName, String role) {
    static WaitingOnResponse from(WorkDeskWaitingOn value) {
      return new WaitingOnResponse(value.principalId(),
          value.displayName().orElse(null), value.role().orElse(null));
    }
  }
}
