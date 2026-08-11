package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.conversation.ConversationIdAndTaskIntentId;
import io.crewscope.application.conversation.ConfirmTaskIntentCommand;
import io.crewscope.application.conversation.RejectTaskIntentCommand;
import io.crewscope.application.conversation.ReviseTaskIntentCommand;
import io.crewscope.application.conversation.TaskIntentApplicationService;
import io.crewscope.application.conversation.TaskIntentConfirmationCommandPort;
import io.crewscope.application.conversation.TaskIntentConfirmationPreview;
import io.crewscope.application.conversation.TaskIntentV1;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentDecision;
import io.crewscope.domain.conversation.TaskIntentId;
import io.crewscope.domain.conversation.TaskIntentProposal;
import io.crewscope.domain.conversation.TaskIntentResponsibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Conversation-scoped TaskIntent review API backed only by current server-side facts. */
@RestController
@RequestMapping(
    "/api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents")
public final class TaskIntentController {

  private final TaskIntentApplicationService service;
  private final TaskIntentConfirmationCommandPort confirmationService;
  private final TeamRequestIdentityResolver identityResolver;

  public TaskIntentController(
      TaskIntentApplicationService service,
      TaskIntentConfirmationCommandPort confirmationService,
      TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.confirmationService = confirmationService;
    this.identityResolver = identityResolver;
  }

  @GetMapping("/{taskIntentId}")
  public Mono<ResponseEntity<TaskIntentResponse>> get(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      @PathVariable String taskIntentId,
      Authentication authentication,
      ServerWebExchange exchange) {
    Route route = route(organizationId, teamId, conversationId, taskIntentId);
    return query(
            authentication,
            route.organizationId(),
            exchange,
            access ->
                service.get(
                    access, route.organizationId(), route.teamId(), route.target()))
        .map(
            intent ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(ApiHeaders.ETAG, ApiHeaders.versionEtag(intent.version()))
                    .body(TaskIntentResponse.from(intent)));
  }

  @PostMapping("/{taskIntentId}/revisions")
  public Mono<ResponseEntity<CommandReceiptResponse>> revise(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      @PathVariable String taskIntentId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody TaskIntentV1 request,
      Authentication authentication,
      ServerWebExchange exchange) {
    Route route = route(organizationId, teamId, conversationId, taskIntentId);
    return command(
        authentication,
        route.organizationId(),
        ApiHeaders.requireIdempotencyKey(key),
        exchange,
        context ->
            service.revise(
                context,
                route.teamId(),
                route.target(),
                new ReviseTaskIntentCommand(request),
                ApiHeaders.requireIfMatch(ifMatch)));
  }

  @PostMapping("/{taskIntentId}/confirmation-previews")
  public Mono<ResponseEntity<TaskIntentConfirmationPreviewResponse>> previewConfirmation(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      @PathVariable String taskIntentId,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication authentication,
      ServerWebExchange exchange) {
    Route route = route(organizationId, teamId, conversationId, taskIntentId);
    long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
    return query(
            authentication,
            route.organizationId(),
            exchange,
            access ->
                service.previewConfirmation(
                    access,
                    route.organizationId(),
                    route.teamId(),
                    route.target(),
                    expectedVersion))
        .map(
            preview ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(
                        ApiHeaders.ETAG,
                        ApiHeaders.versionEtag(preview.taskIntent().version()))
                    .body(TaskIntentConfirmationPreviewResponse.from(preview)));
  }

  @PostMapping("/{taskIntentId}/confirmations")
  public Mono<ResponseEntity<CommandReceiptResponse>> confirm(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      @PathVariable String taskIntentId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) String requestBody,
      Authentication authentication,
      ServerWebExchange exchange) {
    Route route = route(organizationId, teamId, conversationId, taskIntentId);
    requireEmptyRequestBody(requestBody);
    return command(
        authentication,
        route.organizationId(),
        ApiHeaders.requireIdempotencyKey(key),
        exchange,
        context ->
            confirmationService.confirm(
                context,
                route.teamId(),
                route.target(),
                new ConfirmTaskIntentCommand(ApiHeaders.requireIfMatch(ifMatch))));
  }

  private static void requireEmptyRequestBody(String requestBody) {
    if (requestBody != null && !requestBody.isEmpty()) {
      throw new ApiRequestException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Request body must be empty",
          Map.of("field", "body"));
    }
  }

  @PostMapping("/{taskIntentId}/rejections")
  public Mono<ResponseEntity<CommandReceiptResponse>> reject(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String conversationId,
      @PathVariable String taskIntentId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody RejectTaskIntentRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    Route route = route(organizationId, teamId, conversationId, taskIntentId);
    return command(
        authentication,
        route.organizationId(),
        ApiHeaders.requireIdempotencyKey(key),
        exchange,
        context ->
            service.reject(
                context,
                route.teamId(),
                route.target(),
                new RejectTaskIntentCommand(request.reason()),
                ApiHeaders.requireIfMatch(ifMatch)));
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

  private static Route route(
      String organizationId, String teamId, String conversationId, String taskIntentId) {
    try {
      OrganizationId organization = OrganizationId.from(organizationId);
      TeamId team = TeamId.from(teamId);
      return new Route(
          organization,
          team,
          new ConversationIdAndTaskIntentId(
              ConversationId.from(conversationId), TaskIntentId.from(taskIntentId)));
    } catch (IllegalArgumentException failure) {
      throw new ApiRequestException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Request contains an invalid identifier",
          Map.of("route", "task-intents"));
    }
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  private record Route(
      OrganizationId organizationId,
      TeamId teamId,
      ConversationIdAndTaskIntentId target) {}

  public record RejectTaskIntentRequest(
      @NotBlank @Size(max = TaskIntentDecision.MAX_REASON_LENGTH) String reason) {}

  public record TaskIntentConfirmationPreviewResponse(
      boolean confirmable,
      String taskIntentId,
      int proposalRevision,
      long version,
      String confirmingPrincipalId,
      TaskIntentProposalResponse proposal) {

    static TaskIntentConfirmationPreviewResponse from(TaskIntentConfirmationPreview preview) {
      return new TaskIntentConfirmationPreviewResponse(
          true,
          preview.taskIntent().id().toString(),
          preview.taskIntent().proposalRevision(),
          preview.taskIntent().version(),
          preview.confirmingPrincipalId().toString(),
          TaskIntentProposalResponse.from(preview.validatedProposal()));
    }
  }

  public record TaskIntentResponse(
      String id,
      String conversationId,
      String proposedByPrincipalId,
      int schemaVersion,
      int proposalRevision,
      String status,
      long version,
      TaskIntentProposalResponse proposal,
      TaskIntentDecisionResponse decision,
      String createdAt,
      String updatedAt) {

    static TaskIntentResponse from(TaskIntent intent) {
      return new TaskIntentResponse(
          intent.id().toString(),
          intent.conversationId().toString(),
          intent.proposedByPrincipalId().toString(),
          intent.schemaVersion(),
          intent.proposalRevision(),
          intent.status().name(),
          intent.version(),
          TaskIntentProposalResponse.from(intent.proposal()),
          intent.decision().map(TaskIntentDecisionResponse::from).orElse(null),
          intent.audit().createdAt().toString(),
          intent.audit().updatedAt().toString());
    }
  }

  public record TaskIntentProposalResponse(
      String workProjectId,
      String objective,
      List<String> acceptanceCriteria,
      TaskIntentResponsibilityResponse owner,
      TaskIntentResponsibilityResponse executor,
      TaskIntentResponsibilityResponse gateReviewer) {

    static TaskIntentProposalResponse from(TaskIntentProposal proposal) {
      return new TaskIntentProposalResponse(
          proposal.targetScope().projectId().toString(),
          proposal.objective(),
          proposal.acceptanceCriteria(),
          TaskIntentResponsibilityResponse.from(proposal.owner()),
          proposal.executor().map(TaskIntentResponsibilityResponse::from).orElse(null),
          proposal.gateReviewer().map(TaskIntentResponsibilityResponse::from).orElse(null));
    }
  }

  public record TaskIntentResponsibilityResponse(
      String role, String principalId, String principalType, String teamMemberId) {

    static TaskIntentResponsibilityResponse from(TaskIntentResponsibility responsibility) {
      return new TaskIntentResponsibilityResponse(
          responsibility.role().name(),
          responsibility.principalId().toString(),
          responsibility.principalType().name(),
          responsibility.memberId().map(Object::toString).orElse(null));
    }
  }

  public record TaskIntentDecisionResponse(
      String status, String decidedByPrincipalId, String decidedAt, String reason) {

    static TaskIntentDecisionResponse from(TaskIntentDecision decision) {
      return new TaskIntentDecisionResponse(
          decision.status().name(),
          decision.decidedByPrincipalId().toString(),
          decision.decidedAt().toString(),
          decision.reason().orElse(null));
    }
  }
}
