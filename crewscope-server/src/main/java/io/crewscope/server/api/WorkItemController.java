package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.workitem.CreateNativeWorkItemCommand;
import io.crewscope.application.workitem.TransitionWorkItemCommand;
import io.crewscope.application.workitem.WorkItemCommandService;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemLabel;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** HTTP boundary for native WorkItem creation and optimistic state transitions. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects")
public final class WorkItemController {

  private final WorkItemCommandService service;
  private final TeamRequestIdentityResolver identityResolver;

  public WorkItemController(
      WorkItemCommandService service, TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @PostMapping("/{projectId}/work-items")
  public Mono<ResponseEntity<CommandReceiptResponse>> create(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody CreateWorkItemRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    CreateNativeWorkItemCommand command =
        new CreateNativeWorkItemCommand(
            request.key(),
            request.type(),
            request.title(),
            Optional.ofNullable(request.description()),
            request.priority(),
            request.labels().stream().map(WorkItemLabel::new).collect(Collectors.toSet()),
            Optional.ofNullable(request.dueAt()).map(UtcTimestamp::from));
    return command(
        authentication,
        organization,
        idempotencyKey,
        exchange,
        context -> service.create(context, team, project, command));
  }

  @PostMapping("/{projectId}/work-items/{workItemId}/transitions")
  public Mono<ResponseEntity<CommandReceiptResponse>> transition(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody TransitionWorkItemRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    WorkItemId workItem = workItemId(workItemId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
    return command(
        authentication,
        organization,
        idempotencyKey,
        exchange,
        context ->
            service.transition(
                context,
                team,
                project,
                workItem,
                new TransitionWorkItemCommand(request.targetStatus(), expectedVersion)));
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

  public record CreateWorkItemRequest(
      @NotBlank
          @Size(max = WorkItemKey.MAX_LENGTH)
          @Pattern(regexp = WorkItemKey.FORMAT_REGEX)
          String key,
      @NotNull WorkItemType type,
      @NotBlank @Size(max = WorkItem.MAX_TITLE_LENGTH) String title,
      @Size(max = WorkItem.MAX_DESCRIPTION_LENGTH) String description,
      @NotNull WorkItemPriority priority,
      @NotNull @Size(max = WorkItem.MAX_LABELS)
          Set<@NotBlank @Size(max = WorkItemLabel.MAX_LENGTH) String> labels,
      Instant dueAt) {}

  public record TransitionWorkItemRequest(@NotNull WorkItemStatus targetStatus) {}
}
