package io.crewscope.server.api;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.responsibility.ResponsibilityHandoverApplicationService;
import io.crewscope.application.responsibility.ResponsibilityHandoverApplicationService.HandoverJobCreated;
import io.crewscope.application.responsibility.ResponsibilityHandoverApplicationService.HandoverJobView;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItem;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJob;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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

/**
 * HTTP boundary for ADR-038 responsibility handover: preview one member's active assignments of
 * a role, queue them as a durable job under a caller-generated Idempotency-Key, and process,
 * inspect or cancel the job. Processing replays the ordinary responsibility commands item by
 * item, so responses report per-item DONE/CONFLICT/DENIED outcomes instead of hiding them.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams")
public final class ResponsibilityHandoverController {

  private final ResponsibilityHandoverApplicationService service;
  private final TeamRequestIdentityResolver identityResolver;

  public ResponsibilityHandoverController(
      ResponsibilityHandoverApplicationService service,
      TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  /** Lists the source member's active assignments of one role for the confirmation page. */
  @GetMapping("/{teamId}/members/{memberId}/responsibilities")
  public Mono<ResponseEntity<List<MemberResponsibilityResponse>>> preview(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String memberId,
      @RequestParam(name = "role", required = false) String role,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    Optional<ResponsibilityRole> roleFilter =
        role == null ? Optional.empty() : Optional.of(role(role));
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return identityResolver
        .resolve(authentication, organizationId(organizationId), correlationId)
        .flatMap(
            access ->
                blocking(
                    () ->
                        service.preview(
                            queryContext(access, correlationId),
                            team,
                            memberId(memberId),
                            roleFilter)))
        .map(
            values ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(values.stream().map(MemberResponsibilityResponse::from).toList()));
  }

  /** Queues one handover job; replaying the same key returns the original job. */
  @PostMapping("/{teamId}/responsibility-handovers")
  public Mono<ResponseEntity<CreateHandoverJobResponse>> createJob(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody CreateHandoverJobRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return identityResolver
        .resolve(authentication, organizationId(organizationId), correlationId)
        .flatMap(
            access ->
                blocking(
                    () ->
                        service.createJob(
                            new TeamCommandContext(
                                access, idempotencyKey, correlationId, Optional.empty()),
                            team,
                            memberId(request.sourceMemberId()),
                            principalId(request.targetPrincipalId()),
                            role(request.role()))))
        .map(ResponsibilityHandoverController::createdResponse);
  }

  /** Processes every PENDING item in its own short transaction; idempotent and resumable. */
  @PostMapping("/{teamId}/responsibility-handovers/{jobId}/process")
  public Mono<ResponseEntity<HandoverJobResponse>> processJob(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String jobId,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    ResponsibilityHandoverJobId job = jobId(jobId);
    return jobAction(
            authentication,
            organizationId(organizationId),
            exchange,
            context -> service.processJob(context, team, job))
        .map(ResponsibilityHandoverController::okView);
  }

  /** Returns one job and its items at their current processing state. */
  @GetMapping("/{teamId}/responsibility-handovers/{jobId}")
  public Mono<ResponseEntity<HandoverJobResponse>> getJob(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String jobId,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    ResponsibilityHandoverJobId job = jobId(jobId);
    return jobAction(
            authentication,
            organizationId(organizationId),
            exchange,
            context -> service.getJob(context, team, job))
        .map(ResponsibilityHandoverController::okView);
  }

  /** Stops a job that still has unprocessed items; already DONE items are never rolled back. */
  @PostMapping("/{teamId}/responsibility-handovers/{jobId}/cancel")
  public Mono<ResponseEntity<HandoverJobResponse>> cancelJob(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String jobId,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    ResponsibilityHandoverJobId job = jobId(jobId);
    return jobAction(
            authentication,
            organizationId(organizationId),
            exchange,
            context -> service.cancelJob(context, team, job))
        .map(ResponsibilityHandoverController::okView);
  }

  public record CreateHandoverJobRequest(
      @NotBlank String sourceMemberId,
      @NotBlank String targetPrincipalId,
      @NotBlank String role) {}

  /** The 202 acknowledgement together with the queued job, so the caller can process it. */
  public record CreateHandoverJobResponse(
      CommandReceiptResponse receipt, HandoverJobResponse job) {}

  public record HandoverJobResponse(
      String jobId,
      String status,
      String role,
      String sourceMemberId,
      String targetPrincipalId,
      long sourceAuthorizationVersion,
      long version,
      List<HandoverItemResponse> items) {

    static HandoverJobResponse from(
        ResponsibilityHandoverJob job, List<ResponsibilityHandoverItem> items) {
      return new HandoverJobResponse(
          job.id().toString(),
          job.status().name(),
          job.role().name(),
          job.sourceMemberId().toString(),
          job.targetPrincipalId().toString(),
          job.sourceAuthorizationVersion(),
          job.version(),
          items.stream().map(HandoverItemResponse::from).toList());
    }
  }

  public record HandoverItemResponse(
      String itemId,
      String assignmentId,
      String workItemId,
      String state,
      String resultAssignmentId,
      String errorCode) {

    static HandoverItemResponse from(ResponsibilityHandoverItem item) {
      return new HandoverItemResponse(
          item.id().toString(),
          item.assignmentId().toString(),
          item.workItemId().toString(),
          item.state().name(),
          item.resultAssignmentId().map(Objects::toString).orElse(null),
          item.errorCode().orElse(null));
    }
  }

  public record MemberResponsibilityResponse(
      String assignmentId, String workItemId, String role, long version) {

    static MemberResponsibilityResponse from(ResponsibilityAssignment value) {
      return new MemberResponsibilityResponse(
          value.id().toString(), value.workItemId().toString(), value.role().name(),
          value.version());
    }
  }

  private static ResponseEntity<CreateHandoverJobResponse> createdResponse(
      HandoverJobCreated created) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.accepted().cacheControl(CacheControl.noStore());
    if (created.replayed()) {
      response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
    }
    return response.body(
        new CreateHandoverJobResponse(
            CommandReceiptResponse.from(created.receipt()),
            HandoverJobResponse.from(created.job(), created.items())));
  }

  /**
   * Job operations are naturally idempotent through the job's own state machine, so the
   * idempotency key only labels the request for tracing instead of gating it.
   */
  private Mono<HandoverJobView> jobAction(
      Authentication authentication,
      OrganizationId organizationId,
      ServerWebExchange exchange,
      Function<TeamCommandContext, HandoverJobView> action) {
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return identityResolver
        .resolve(authentication, organizationId, correlationId)
        .flatMap(
            access ->
                blocking(
                    () -> action.apply(queryContext(access, correlationId))));
  }

  private static TeamCommandContext queryContext(
      io.crewscope.application.team.TeamAccessContext access, UUID correlationId) {
    return new TeamCommandContext(
        access,
        new IdempotencyKey("handover-query:" + UUID.randomUUID()),
        correlationId,
        Optional.empty());
  }

  private static ResponseEntity<HandoverJobResponse> okView(HandoverJobView view) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(HandoverJobResponse.from(view.job(), view.items()));
  }

  private static <T> Mono<T> blocking(Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  private static OrganizationId organizationId(String value) {
    try {
      return OrganizationId.from(value);
    } catch (IllegalArgumentException invalid) {
      throw new ApiRequestException(
          HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Request contains an invalid identifier",
          Map.of("field", "organizationId"));
    }
  }

  private static TeamId teamId(String value) {
    return new TeamId(uuid(value, "teamId"));
  }

  private static TeamMemberId memberId(String value) {
    return new TeamMemberId(uuid(value, "memberId"));
  }

  private static PrincipalId principalId(String value) {
    return new PrincipalId(uuid(value, "targetPrincipalId"));
  }

  private static ResponsibilityHandoverJobId jobId(String value) {
    return ResponsibilityHandoverJobId.from(value);
  }

  private static ResponsibilityRole role(String value) {
    try {
      return ResponsibilityRole.valueOf(value);
    } catch (RuntimeException invalid) {
      throw new ApiRequestException(
          HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Request contains an invalid responsibility role",
          Map.of("field", "role"));
    }
  }

  private static UUID uuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (RuntimeException invalid) {
      throw new ApiRequestException(
          HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Request contains an invalid identifier",
          Map.of("field", field));
    }
  }
}
