package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.workgraph.CreateWorkItemRelationCommand;
import io.crewscope.application.workgraph.RemoveWorkItemRelationCommand;
import io.crewscope.application.workgraph.WorkItemRelationCommandService;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workgraph.WorkItemRelationId;
import io.crewscope.domain.workgraph.WorkItemRelationType;
import io.crewscope.domain.workitem.WorkItemId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HTTP boundary for WorkItemRelation commands (M11-A02, ADR-033). One edge spans two
 * WorkProjects, so the resource hangs off the Team, not a project. Relations are immutable
 * version-0 facts: every command is idempotent and none carries If-Match.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/work-item-relations")
public final class WorkGraphController {

  private final WorkItemRelationCommandService service;
  private final TeamRequestIdentityResolver identityResolver;

  public WorkGraphController(
      WorkItemRelationCommandService service, TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @PostMapping
  public Mono<ResponseEntity<CommandReceiptResponse>> create(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody CreateRelationRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    CreateWorkItemRelationCommand command =
        new CreateWorkItemRelationCommand(
            request.type(), workItemId(request.fromWorkItemId(), "fromWorkItemId"),
            workItemId(request.toWorkItemId(), "toWorkItemId"));
    return command(
        authentication,
        organization,
        idempotencyKey,
        exchange,
        context -> service.create(context, team, command));
  }

  @DeleteMapping("/{relationId}")
  public Mono<ResponseEntity<CommandReceiptResponse>> remove(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String relationId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    RemoveWorkItemRelationCommand command =
        new RemoveWorkItemRelationCommand(relationId(relationId));
    return command(
        authentication,
        organization,
        idempotencyKey,
        exchange,
        context -> service.remove(context, team, command));
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

  private static WorkItemId workItemId(String value, String field) {
    try {
      return WorkItemId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier(field);
    }
  }

  private static WorkItemRelationId relationId(String value) {
    try {
      return WorkItemRelationId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("relationId");
    }
  }

  private static ApiRequestException invalidIdentifier(String field) {
    return new ApiRequestException(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Request contains an invalid identifier",
        Map.of("field", field));
  }

  /**
   * The submission direction: {@code X DEPENDS_ON Y} is normalized into the stored
   * {@code BLOCKS(Y, X)} edge by the aggregate, so the wire accepts all three kinds.
   */
  public record CreateRelationRequest(
      @NotNull WorkItemRelationType type,
      @NotBlank String fromWorkItemId,
      @NotBlank String toWorkItemId) {}
}
