package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.responsibility.AssignResponsibilityCommand;
import io.crewscope.application.responsibility.ReleaseResponsibilityCommand;
import io.crewscope.application.responsibility.ReplaceOwnerCommand;
import io.crewscope.application.responsibility.ResponsibilityAssignmentView;
import io.crewscope.application.responsibility.ResponsibilityCommandService;
import io.crewscope.application.responsibility.ResponsibilityQueryService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.responsibility.ActiveOwnerExpectation;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** HTTP boundary for active WorkItem responsibility management and policy-safe commands. */
@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}"
        + "/work-items/{workItemId}/responsibilities")
public final class ResponsibilityController {

  private final ResponsibilityQueryService queryService;
  private final ResponsibilityCommandService commandService;
  private final TeamRequestIdentityResolver identityResolver;

  public ResponsibilityController(
      ResponsibilityQueryService queryService,
      ResponsibilityCommandService commandService,
      TeamRequestIdentityResolver identityResolver) {
    this.queryService = queryService;
    this.commandService = commandService;
    this.identityResolver = identityResolver;
  }

  @GetMapping
  public Mono<ResponseEntity<List<ResponsibilityResponse>>> list(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      Authentication authentication,
      ServerWebExchange exchange) {
    Scope scope = scope(organizationId, teamId, projectId, workItemId);
    return query(
            authentication,
            scope.organizationId(),
            exchange,
            access ->
                queryService.listActive(
                    access,
                    scope.organizationId(),
                    scope.teamId(),
                    scope.projectId(),
                    scope.workItemId()))
        .map(
            values ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(values.stream().map(ResponsibilityResponse::from).toList()));
  }

  @PostMapping("/owner")
  public Mono<ResponseEntity<CommandReceiptResponse>> replaceOwner(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody ReplaceOwnerRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    Scope scope = scope(organizationId, teamId, projectId, workItemId);
    ReplaceOwnerCommand ownerCommand =
        new ReplaceOwnerCommand(
            principalId(request.actorPrincipalId(), "actorPrincipalId"), request.expectation());
    return command(
        authentication,
        scope.organizationId(),
        key,
        exchange,
        context ->
            commandService.replaceOwner(
                context,
                scope.teamId(),
                scope.projectId(),
                scope.workItemId(),
                ownerCommand));
  }

  @PostMapping("/executors")
  public Mono<ResponseEntity<CommandReceiptResponse>> assignExecutor(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody AssignResponsibilityRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    Scope scope = scope(organizationId, teamId, projectId, workItemId);
    return assign(
        authentication,
        scope,
        key,
        request,
        exchange,
        (context, command) ->
            commandService.assignExecutor(
                context, scope.teamId(), scope.projectId(), scope.workItemId(), command));
  }

  @PostMapping("/gate-reviewers")
  public Mono<ResponseEntity<CommandReceiptResponse>> assignGateReviewer(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody AssignResponsibilityRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    Scope scope = scope(organizationId, teamId, projectId, workItemId);
    return assign(
        authentication,
        scope,
        key,
        request,
        exchange,
        (context, command) ->
            commandService.assignGateReviewer(
                context, scope.teamId(), scope.projectId(), scope.workItemId(), command));
  }

  @PostMapping("/advisory-reviewers")
  public Mono<ResponseEntity<CommandReceiptResponse>> assignAdvisoryReviewer(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody AssignResponsibilityRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    Scope scope = scope(organizationId, teamId, projectId, workItemId);
    return assign(
        authentication,
        scope,
        key,
        request,
        exchange,
        (context, command) ->
            commandService.assignAdvisoryReviewer(
                context, scope.teamId(), scope.projectId(), scope.workItemId(), command));
  }

  @PostMapping("/{assignmentId}/releases")
  public Mono<ResponseEntity<CommandReceiptResponse>> release(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      @PathVariable String workItemId,
      @PathVariable String assignmentId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication authentication,
      ServerWebExchange exchange) {
    Scope scope = scope(organizationId, teamId, projectId, workItemId);
    ResponsibilityAssignmentId assignment = assignmentId(assignmentId);
    ReleaseResponsibilityCommand releaseCommand =
        new ReleaseResponsibilityCommand(ApiHeaders.requireIfMatch(ifMatch));
    return command(
        authentication,
        scope.organizationId(),
        key,
        exchange,
        context ->
            commandService.release(
                context,
                scope.teamId(),
                scope.projectId(),
                scope.workItemId(),
                assignment,
                releaseCommand));
  }

  private Mono<ResponseEntity<CommandReceiptResponse>> assign(
      Authentication authentication,
      Scope scope,
      String key,
      AssignResponsibilityRequest request,
      ServerWebExchange exchange,
      AssignmentEndpoint action) {
    AssignResponsibilityCommand assignmentCommand =
        new AssignResponsibilityCommand(
            principalId(request.actorPrincipalId(), "actorPrincipalId"));
    return command(
        authentication,
        scope.organizationId(),
        key,
        exchange,
        context -> action.apply(context, assignmentCommand));
  }

  private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
      Authentication authentication,
      OrganizationId organizationId,
      String key,
      ServerWebExchange exchange,
      Function<TeamCommandContext, CommandExecution<T>> action) {
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
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

  private static Scope scope(
      String organizationId, String teamId, String projectId, String workItemId) {
    return new Scope(
        organizationId(organizationId),
        teamId(teamId),
        projectId(projectId),
        workItemId(workItemId));
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

  private static ResponsibilityAssignmentId assignmentId(String value) {
    try {
      return ResponsibilityAssignmentId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("assignmentId");
    }
  }

  private static PrincipalId principalId(String value, String field) {
    try {
      return PrincipalId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier(field);
    }
  }

  private static ApiRequestException invalidIdentifier(String field) {
    return new ApiRequestException(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Request contains an invalid identifier",
        Map.of("field", field));
  }

  public record ReplaceOwnerRequest(
      @NotBlank String actorPrincipalId,
      String expectedAssignmentId,
      @PositiveOrZero Long expectedVersion) {

    ActiveOwnerExpectation expectation() {
      if (expectedAssignmentId == null && expectedVersion == null) {
        return ActiveOwnerExpectation.none();
      }
      if (expectedAssignmentId == null || expectedVersion == null) {
        throw new ApiRequestException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "invalid_request",
            "Owner expectation requires both assignment identity and version",
            Map.of("field", "expectedAssignmentId"));
      }
      return ActiveOwnerExpectation.at(
          assignmentId(expectedAssignmentId), expectedVersion.longValue());
    }
  }

  public record AssignResponsibilityRequest(@NotBlank String actorPrincipalId) {}

  public record ResponsibilityResponse(
      String id,
      String workItemId,
      String role,
      String actorPrincipalId,
      String actorType,
      String actorMemberId,
      String actorDisplayName,
      String status,
      String assignedByPrincipalId,
      String assignedAt,
      String acceptedAt,
      long version) {

    static ResponsibilityResponse from(ResponsibilityAssignmentView view) {
      ResponsibilityAssignment assignment = view.assignment();
      return new ResponsibilityResponse(
          assignment.id().toString(),
          assignment.workItemId().toString(),
          assignment.role().name(),
          assignment.actorPrincipalId().toString(),
          assignment.actorType().name(),
          assignment.actorMemberId().map(Object::toString).orElse(null),
          view.actorDisplayName(),
          assignment.status().name(),
          assignment.assignedByPrincipalId().toString(),
          assignment.assignedAt().toString(),
          assignment.acceptedAt().toString(),
          assignment.version());
    }
  }

  private record Scope(
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId) {}

  @FunctionalInterface
  private interface AssignmentEndpoint {
    CommandExecution<?> apply(
        TeamCommandContext context, AssignResponsibilityCommand command);
  }
}
