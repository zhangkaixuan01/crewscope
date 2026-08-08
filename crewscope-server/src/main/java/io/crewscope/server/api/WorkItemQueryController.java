package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.workitem.AddWorkItemCommentCommand;
import io.crewscope.application.workitem.LinkWorkItemResourceCommand;
import io.crewscope.application.workitem.WorkItemCollaborationService;
import io.crewscope.application.workitem.WorkItemCursor;
import io.crewscope.application.workitem.WorkItemDetails;
import io.crewscope.application.workitem.WorkItemPage;
import io.crewscope.application.workitem.WorkItemQueryService;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceType;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** HTTP boundary for WorkItem discovery and immutable collaboration children. */
@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items")
public final class WorkItemQueryController {

  private final WorkItemQueryService queryService;
  private final WorkItemCollaborationService collaborationService;
  private final TeamRequestIdentityResolver identityResolver;
  private final WorkItemCursorCodec cursorCodec = new WorkItemCursorCodec();

  public WorkItemQueryController(
      WorkItemQueryService queryService,
      WorkItemCollaborationService collaborationService,
      TeamRequestIdentityResolver identityResolver) {
    this.queryService = queryService;
    this.collaborationService = collaborationService;
    this.identityResolver = identityResolver;
  }

  @GetMapping
  public Mono<ResponseEntity<WorkItemPageResponse>> list(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @RequestParam(required = false) WorkItemStatus status,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    Optional<WorkItemCursor> cursor = Optional.ofNullable(after).map(cursorCodec::decode);
    int pageSize = ApiPagination.limit(limit);
    return query(
            authentication,
            organization,
            exchange,
            access ->
                queryService.list(
                    access,
                    organization,
                    team,
                    project,
                    Optional.ofNullable(status),
                    cursor,
                    pageSize))
        .map(
            page ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(WorkItemPageResponse.from(page, cursorCodec)));
  }

  @GetMapping("/{workItemId}")
  public Mono<ResponseEntity<WorkItemDetailsResponse>> get(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    WorkItemId item = workItemId(workItemId);
    return details(authentication, organization, team, project, item, exchange)
        .map(
            value ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .eTag(Long.toString(value.workItem().version()))
                    .body(WorkItemDetailsResponse.from(value)));
  }

  @GetMapping("/{workItemId}/comments")
  public Mono<ResponseEntity<List<WorkItemCommentResponse>>> comments(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      Authentication authentication,
      ServerWebExchange exchange) {
    return details(
            authentication,
            organizationId(organizationId),
            teamId(teamId),
            projectId(projectId),
            workItemId(workItemId),
            exchange)
        .map(
            value ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(
                        value.comments().stream()
                            .map(WorkItemCommentResponse::from)
                            .toList()));
  }

  @GetMapping("/{workItemId}/resource-links")
  public Mono<ResponseEntity<List<WorkItemResourceLinkResponse>>> resourceLinks(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      Authentication authentication,
      ServerWebExchange exchange) {
    return details(
            authentication,
            organizationId(organizationId),
            teamId(teamId),
            projectId(projectId),
            workItemId(workItemId),
            exchange)
        .map(
            value ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(
                        value.resourceLinks().stream()
                            .map(WorkItemResourceLinkResponse::from)
                            .toList()));
  }

  @PostMapping("/{workItemId}/comments")
  public Mono<ResponseEntity<CommandReceiptResponse>> addComment(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody AddCommentRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    WorkItemId item = workItemId(workItemId);
    return command(
        authentication,
        organization,
        ApiHeaders.requireIdempotencyKey(key),
        exchange,
        context ->
            collaborationService.addComment(
                context, team, project, item, new AddWorkItemCommentCommand(request.content())));
  }

  @PostMapping("/{workItemId}/resource-links")
  public Mono<ResponseEntity<CommandReceiptResponse>> linkResource(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody LinkResourceRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    WorkItemId item = workItemId(workItemId);
    return command(
        authentication,
        organization,
        ApiHeaders.requireIdempotencyKey(key),
        exchange,
        context ->
            collaborationService.linkResource(
                context,
                team,
                project,
                item,
                new LinkWorkItemResourceCommand(
                    request.resourceType(),
                    request.resourceReference(),
                    Optional.ofNullable(request.label()))));
  }

  private Mono<WorkItemDetails> details(
      Authentication authentication,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      ServerWebExchange exchange) {
    return query(
        authentication,
        organizationId,
        exchange,
        access -> queryService.get(access, organizationId, teamId, projectId, workItemId));
  }

  private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
      Authentication authentication,
      OrganizationId organizationId,
      IdempotencyKey idempotencyKey,
      ServerWebExchange exchange,
      Function<TeamCommandContext, CommandExecution<T>> action) {
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return identityResolver
        .resolve(authentication, organizationId, correlationId)
        .flatMap(
            access ->
                blocking(
                    () ->
                        action.apply(
                            new TeamCommandContext(
                                access, idempotencyKey, correlationId, Optional.empty()))))
        .map(CommandReceiptResponse::accepted);
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

  public record AddCommentRequest(
      @NotBlank @Size(max = WorkItemComment.MAX_CONTENT_LENGTH) String content) {}

  public record LinkResourceRequest(
      @NotNull WorkItemResourceType resourceType,
      @NotBlank @Size(max = WorkItemResourceLink.MAX_REFERENCE_LENGTH) String resourceReference,
      @Size(max = WorkItemResourceLink.MAX_LABEL_LENGTH) String label) {}

  public record WorkItemPageResponse(List<WorkItemResponse> items, String nextCursor) {

    static WorkItemPageResponse from(WorkItemPage page, WorkItemCursorCodec cursorCodec) {
      return new WorkItemPageResponse(
          page.items().stream().map(WorkItemResponse::from).toList(),
          page.nextCursor().map(cursorCodec::encode).orElse(null));
    }
  }

  public record WorkItemDetailsResponse(
      WorkItemResponse workItem,
      List<WorkItemCommentResponse> comments,
      List<WorkItemResourceLinkResponse> resourceLinks) {

    static WorkItemDetailsResponse from(WorkItemDetails details) {
      return new WorkItemDetailsResponse(
          WorkItemResponse.from(details.workItem()),
          details.comments().stream().map(WorkItemCommentResponse::from).toList(),
          details.resourceLinks().stream().map(WorkItemResourceLinkResponse::from).toList());
    }
  }

  public record WorkItemResponse(
      String id,
      String organizationId,
      String teamId,
      String workspaceId,
      String projectId,
      String key,
      String type,
      String title,
      String description,
      String status,
      String priority,
      List<String> labels,
      String dueAt,
      String source,
      String sourceReference,
      long version,
      String createdAt,
      String createdByPrincipalId,
      String updatedAt,
      String updatedByPrincipalId) {

    static WorkItemResponse from(WorkItem item) {
      return new WorkItemResponse(
          item.id().toString(),
          item.scope().organizationId().toString(),
          item.scope().teamId().toString(),
          item.scope().workspaceId().toString(),
          item.scope().projectId().toString(),
          item.key().value(),
          item.type().name(),
          item.title(),
          item.description().orElse(null),
          item.status().name(),
          item.priority().name(),
          item.labels().stream()
              .map(label -> label.value())
              .sorted()
              .toList(),
          item.dueAt().map(Object::toString).orElse(null),
          item.source().name(),
          item.sourceReference().orElse(null),
          item.version(),
          item.audit().createdAt().toString(),
          item.audit().createdBy().map(Object::toString).orElse(null),
          item.audit().updatedAt().toString(),
          item.audit().updatedBy().map(Object::toString).orElse(null));
    }
  }

  public record WorkItemCommentResponse(
      String id,
      String workItemId,
      String authorPrincipalId,
      String content,
      String source,
      String externalId,
      String createdAt) {

    static WorkItemCommentResponse from(WorkItemComment comment) {
      return new WorkItemCommentResponse(
          comment.id().toString(),
          comment.workItemId().toString(),
          comment.authorPrincipalId().toString(),
          comment.content(),
          comment.source().name(),
          comment.externalId().orElse(null),
          comment.audit().createdAt().toString());
    }
  }

  public record WorkItemResourceLinkResponse(
      String id,
      String workItemId,
      String resourceType,
      String resourceReference,
      String label,
      String createdAt,
      String createdByPrincipalId) {

    static WorkItemResourceLinkResponse from(WorkItemResourceLink link) {
      return new WorkItemResourceLinkResponse(
          link.id().toString(),
          link.workItemId().toString(),
          link.resourceType().name(),
          link.resourceReference(),
          link.label().orElse(null),
          link.audit().createdAt().toString(),
          link.audit().createdBy().map(Object::toString).orElse(null));
    }
  }
}
