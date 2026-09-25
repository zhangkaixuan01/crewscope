package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMemberLifecycleApplicationService;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.TeamMemberId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
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

/**
 * HTTP boundary for ADR-038 member lifecycle commands: suspension, activation, removal, leaving,
 * role grants and ownership transfer. Every command carries the target member's optimistic
 * version as If-Match and a caller-generated Idempotency-Key; the Team row lock serializes
 * concurrent commands against the same Team. Responses follow the shared 202 command receipt
 * contract — the member list projection is re-read afterwards, as with every Team command.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams")
public final class TeamMemberLifecycleController {

  private final TeamMemberLifecycleApplicationService service;
  private final TeamRequestIdentityResolver identityResolver;

  public TeamMemberLifecycleController(
      TeamMemberLifecycleApplicationService service,
      TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @PostMapping("/{teamId}/members/{memberId}/suspend")
  public Mono<ResponseEntity<CommandReceiptResponse>> suspendMember(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String memberId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    return command(
        authentication,
        organizationId(organizationId),
        key,
        exchange,
        context ->
            service.suspendMember(context, team, memberId(memberId), ApiHeaders.requireIfMatch(ifMatch)));
  }

  @PostMapping("/{teamId}/members/{memberId}/activate")
  public Mono<ResponseEntity<CommandReceiptResponse>> activateMember(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String memberId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    return command(
        authentication,
        organizationId(organizationId),
        key,
        exchange,
        context ->
            service.activateMember(context, team, memberId(memberId), ApiHeaders.requireIfMatch(ifMatch)));
  }

  @PostMapping("/{teamId}/members/{memberId}/remove")
  public Mono<ResponseEntity<CommandReceiptResponse>> removeMember(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String memberId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    return command(
        authentication,
        organizationId(organizationId),
        key,
        exchange,
        context ->
            service.removeMember(context, team, memberId(memberId), ApiHeaders.requireIfMatch(ifMatch)));
  }

  @PostMapping("/{teamId}/members/me/leave")
  public Mono<ResponseEntity<CommandReceiptResponse>> leaveTeam(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    return command(
        authentication,
        organizationId(organizationId),
        key,
        exchange,
        context -> service.leaveTeam(context, team, ApiHeaders.requireIfMatch(ifMatch)));
  }

  @PostMapping("/{teamId}/members/{memberId}/roles")
  public Mono<ResponseEntity<CommandReceiptResponse>> grantRole(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String memberId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody GrantRoleRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    return command(
        authentication,
        organizationId(organizationId),
        key,
        exchange,
        context ->
            service.grantRole(
                context,
                team,
                memberId(memberId),
                ApiHeaders.requireIfMatch(ifMatch),
                request.roleKey()));
  }

  @PostMapping("/{teamId}/members/{memberId}/roles/{grantId}/revoke")
  public Mono<ResponseEntity<CommandReceiptResponse>> revokeRole(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String memberId,
      @PathVariable String grantId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    return command(
        authentication,
        organizationId(organizationId),
        key,
        exchange,
        context ->
            service.revokeRole(
                context,
                team,
                memberId(memberId),
                ApiHeaders.requireIfMatch(ifMatch),
                new MemberRoleId(uuid(grantId, "grantId"))));
  }

  @PostMapping("/{teamId}/transfer-ownership")
  public Mono<ResponseEntity<CommandReceiptResponse>> transferOwnership(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody TransferOwnershipRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    TeamId team = teamId(teamId);
    return command(
        authentication,
        organizationId(organizationId),
        key,
        exchange,
        context ->
            service.transferOwnership(
                context,
                team,
                memberId(request.targetMemberId()),
                ApiHeaders.requireIfMatch(ifMatch)));
  }

  public record GrantRoleRequest(@NotBlank String roleKey) {}

  public record TransferOwnershipRequest(@NotBlank String targetMemberId) {}

  private Mono<ResponseEntity<CommandReceiptResponse>> command(
      Authentication authentication,
      OrganizationId organizationId,
      String key,
      ServerWebExchange exchange,
      Function<TeamCommandContext, CommandExecution<?>> action) {
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    UUID correlationId = ApiCorrelationIds.resolve(exchange);
    return identityResolver
        .resolve(authentication, organizationId, correlationId)
        .flatMap(
            access ->
                blocking(
                    () ->
                        action.apply(
                            new TeamCommandContext(access, idempotencyKey, correlationId, Optional.empty()))))
        .map(CommandReceiptResponse::accepted);
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
