package io.crewscope.server.api;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workgraph.BlockedItemPosition;
import io.crewscope.application.workgraph.BlockedWorkItemPage;
import io.crewscope.application.workgraph.BlockedWorkItemRow;
import io.crewscope.application.workgraph.CriticalPathResult;
import io.crewscope.application.workgraph.WorkGraphQueryService;
import io.crewscope.application.workgraph.WorkItemRelationAdjacency;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workgraph.BlockingChain;
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

/**
 * Read side of the WorkGraph API (M11-A02, ADR-033): anchor-relative adjacency, the bounded
 * blocking chain, the pure topological critical path and the keyset-paged "currently blocked"
 * aggregation. Every read is bounded (depth 32 / rows 5000, blocker window 20) and reports
 * truncation explicitly; no read is cacheable.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}")
public final class WorkGraphQueryController {

  private final WorkGraphQueryService queryService;
  private final TeamRequestIdentityResolver identityResolver;
  private final WorkGraphBlockedCursorCodec cursorCodec;

  public WorkGraphQueryController(
      WorkGraphQueryService queryService,
      TeamRequestIdentityResolver identityResolver,
      WorkGraphBlockedCursorCodec cursorCodec) {
    this.queryService = queryService;
    this.identityResolver = identityResolver;
    this.cursorCodec = cursorCodec;
  }

  /** Every stored edge touching one anchor, as anchor-relative OUTGOING/INCOMING views. */
  @GetMapping("work-projects/{projectId}/work-items/{workItemId}/relations")
  public Mono<ResponseEntity<WorkItemRelationsResponse>> relations(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    WorkItemId anchor = workItemId(workItemId);
    return query(
            authentication,
            organization,
            exchange,
            access -> queryService.relations(access, organization, team, project, anchor))
        .map(value -> ok(WorkItemRelationsResponse.from(value)));
  }

  /** The bounded upstream blocking walk of one anchor (depth 32 / rows 5000). */
  @GetMapping("work-projects/{projectId}/work-items/{workItemId}/blocking-chain")
  public Mono<ResponseEntity<BlockingChainResponse>> blockingChain(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    WorkItemId anchor = workItemId(workItemId);
    return query(
            authentication,
            organization,
            exchange,
            access -> queryService.blockingChain(access, organization, team, project, anchor))
        .map(value -> ok(BlockingChainResponse.from(value)));
  }

  /** The pure topological longest blocking chain ending at one anchor; never a schedule. */
  @GetMapping("work-projects/{projectId}/work-items/{workItemId}/critical-path")
  public Mono<ResponseEntity<CriticalPathResponse>> criticalPath(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    WorkItemId anchor = workItemId(workItemId);
    return query(
            authentication,
            organization,
            exchange,
            access -> queryService.criticalPath(access, organization, team, project, anchor))
        .map(value -> ok(CriticalPathResponse.from(value)));
  }

  /**
   * One keyset page of the Team's currently blocked WorkItems. The continuation token is decoded
   * only after the viewer is resolved and is bound to this Team and this project filter.
   */
  @GetMapping("work-graph/blocked-work-items")
  public Mono<ResponseEntity<BlockedWorkItemPageResponse>> blockedWorkItems(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    Optional<WorkProjectId> project =
        projectId == null || projectId.isBlank()
            ? Optional.empty()
            : Optional.of(projectId(projectId));
    int pageSize = ApiPagination.workGraphLimit(limit);
    return query(
            authentication,
            organization,
            exchange,
            access -> {
              Optional<BlockedItemPosition> cursor =
                  Optional.ofNullable(after)
                      .map(
                          token ->
                              cursorCodec.decode(token, organization, team, project));
              return queryService.currentlyBlocked(
                  access, organization, team, project, cursor, pageSize);
            })
        .map(
            page ->
                ok(
                    BlockedWorkItemPageResponse.from(
                        page,
                        page.nextPosition()
                            .map(
                                position ->
                                    project
                                        .map(
                                            filtered ->
                                                cursorCodec.encode(
                                                    organization, team, filtered, position))
                                        .orElseGet(
                                            () ->
                                                cursorCodec.encode(organization, team, position)))
                            .orElse(null))));
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

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
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

  public record WorkItemRelationsResponse(
      String anchor, List<WorkItemRelationResponse> edges, boolean truncatedByRows) {

    static WorkItemRelationsResponse from(WorkItemRelationAdjacency adjacency) {
      return new WorkItemRelationsResponse(
          adjacency.anchor().toString(),
          adjacency.edges().stream().map(WorkItemRelationResponse::from).toList(),
          adjacency.truncatedByRows());
    }
  }

  /** Anchor-relative projection of one stored edge; the record components are the whitelist. */
  public record WorkItemRelationResponse(
      String relationId,
      String direction,
      String otherWorkItemId,
      String otherProjectId,
      boolean crossProject,
      List<RelationViewResponse> views,
      String createdAt) {

    static WorkItemRelationResponse from(WorkItemRelationAdjacency.Edge edge) {
      return new WorkItemRelationResponse(
          edge.relationId().toString(),
          edge.direction().name(),
          edge.otherWorkItemId().toString(),
          edge.otherProjectId().toString(),
          edge.crossProject(),
          edge.views().stream().map(RelationViewResponse::from).toList(),
          edge.createdAt().toString());
    }
  }

  /** One API-facing direction of an edge: stored {@code A BLOCKS B} also reads {@code B DEPENDS_ON A}. */
  public record RelationViewResponse(String type, String fromWorkItemId, String toWorkItemId) {

    static RelationViewResponse from(
        io.crewscope.domain.workgraph.WorkItemRelation.RelationView view) {
      return new RelationViewResponse(
          view.type().name(), view.from().toString(), view.to().toString());
    }
  }

  public record BlockingChainResponse(
      String anchor, List<BlockingStepResponse> steps, boolean truncatedByDepth, boolean truncatedByRows) {

    static BlockingChainResponse from(BlockingChain chain) {
      return new BlockingChainResponse(
          chain.root().toString(),
          chain.steps().stream()
              .map(step -> new BlockingStepResponse(step.node().toString(), step.depth()))
              .toList(),
          chain.truncatedByDepth(),
          chain.truncatedByRows());
    }
  }

  public record BlockingStepResponse(String workItemId, int depth) {}

  public record CriticalPathResponse(
      List<String> chain, boolean truncatedByDepth, boolean truncatedByRows) {

    static CriticalPathResponse from(CriticalPathResult result) {
      return new CriticalPathResponse(
          result.chain().stream().map(Object::toString).toList(),
          result.truncatedByDepth(),
          result.truncatedByRows());
    }
  }

  public record BlockedWorkItemPageResponse(
      List<BlockedWorkItemRowResponse> items, String nextCursor) {

    static BlockedWorkItemPageResponse from(BlockedWorkItemPage page, String nextCursor) {
      return new BlockedWorkItemPageResponse(
          page.items().stream().map(BlockedWorkItemRowResponse::from).toList(), nextCursor);
    }
  }

  public record BlockedWorkItemRowResponse(
      String workItemId,
      String itemKey,
      String title,
      String status,
      String projectId,
      String blockedSince,
      int blockerCount,
      boolean crossesProject,
      List<BlockerResponse> blockers) {

    static BlockedWorkItemRowResponse from(BlockedWorkItemRow row) {
      return new BlockedWorkItemRowResponse(
          row.workItemId().toString(),
          row.itemKey(),
          row.title(),
          row.status().name(),
          row.projectId().toString(),
          row.blockedSince().toString(),
          row.blockerCount(),
          row.crossesProject(),
          row.blockers().stream().map(BlockerResponse::from).toList());
    }
  }

  public record BlockerResponse(String workItemId, String title, String status, String projectId) {

    static BlockerResponse from(BlockedWorkItemRow.Blocker blocker) {
      return new BlockerResponse(
          blocker.workItemId().toString(),
          blocker.title(),
          blocker.status().name(),
          blocker.projectId().toString());
    }
  }
}
