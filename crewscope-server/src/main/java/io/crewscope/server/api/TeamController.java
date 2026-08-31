package io.crewscope.server.api;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.AddTeamMemberCommand;
import io.crewscope.application.team.CompleteTeamInitializationCommand;
import io.crewscope.application.team.CreateTeamCommand;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamApplicationService;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMemberView;
import io.crewscope.application.team.TeamView;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.Workspace;
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

/** HTTP boundary for the M1 Team foundation and member-management use cases. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams")
public final class TeamController {

  private final TeamApplicationService service;
  private final TeamRequestIdentityResolver identityResolver;

  public TeamController(
      TeamApplicationService service, TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @PostMapping
  public Mono<ResponseEntity<CommandReceiptResponse>> createTeam(
      @PathVariable String organizationId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody CreateTeamRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    return command(
        authentication,
        organization,
        idempotencyKey,
        exchange,
        context -> service.createTeam(context, new CreateTeamCommand(request.name())));
  }

  @GetMapping
  public Mono<ResponseEntity<List<TeamResponse>>> listTeams(
      @PathVariable String organizationId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    return query(
            authentication,
            organization,
            exchange,
            access -> service.listTeams(access, organization))
        .map(
            teams ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(teams.stream().map(TeamResponse::from).toList()));
  }

  @GetMapping("/{teamId}")
  public Mono<ResponseEntity<TeamResponse>> getTeam(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    return query(
            authentication,
            organization,
            exchange,
            access -> service.getTeam(access, organization, team))
        .map(
            view ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .eTag(Long.toString(view.version()))
                    .body(TeamResponse.from(view)));
  }

  @PostMapping("/{teamId}/members")
  public Mono<ResponseEntity<CommandReceiptResponse>> addMember(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody AddMemberRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    PrincipalId target = principalId(request.userPrincipalId());
    return command(
        authentication,
        organization,
        idempotencyKey,
        exchange,
        context -> service.addMember(context, team, new AddTeamMemberCommand(target)));
  }

  @GetMapping("/{teamId}/members")
  public Mono<ResponseEntity<List<TeamMemberResponse>>> listMembers(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    return query(
            authentication,
            organization,
            exchange,
            access -> service.listMembers(access, organization, team))
        .map(
            members ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(members.stream().map(TeamMemberResponse::from).toList()));
  }

  @GetMapping("/{teamId}/workspaces/default")
  public Mono<ResponseEntity<WorkspaceResponse>> getDefaultWorkspace(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    return query(
            authentication,
            organization,
            exchange,
            access -> service.getDefaultWorkspace(access, organization, team))
        .map(
            workspace ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .eTag(Long.toString(workspace.version()))
                    .body(WorkspaceResponse.from(workspace)));
  }

  @PostMapping("/{teamId}/initialization")
  public Mono<ResponseEntity<CommandReceiptResponse>> completeInitialization(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody CompleteInitializationRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    PrincipalId owner = principalId(request.ownerPrincipalId());
    return command(
        authentication,
        organization,
        idempotencyKey,
        exchange,
        context ->
            service.completeInitialization(
                context, team, new CompleteTeamInitializationCommand(owner)));
  }

  private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
      Authentication authentication,
      OrganizationId organizationId,
      IdempotencyKey idempotencyKey,
      ServerWebExchange exchange,
      Function<TeamCommandContext, io.crewscope.application.command.CommandExecution<T>> action) {
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

  private static PrincipalId principalId(String value) {
    try {
      return PrincipalId.from(value);
    } catch (IllegalArgumentException exception) {
      throw invalidIdentifier("principalId");
    }
  }

  private static ApiRequestException invalidIdentifier(String field) {
    return new ApiRequestException(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Request contains an invalid identifier",
        Map.of("field", field));
  }

  public record CreateTeamRequest(@NotBlank @Size(max = Team.MAX_NAME_LENGTH) String name) {}

  public record AddMemberRequest(@NotBlank String userPrincipalId) {}

  public record CompleteInitializationRequest(@NotBlank String ownerPrincipalId) {}

  public record TeamResponse(
      String id,
      String organizationId,
      String name,
      String status,
      String initializationStatus,
      String ownerMemberId,
      String defaultWorkspaceId,
      long version) {

    static TeamResponse from(TeamView view) {
      return new TeamResponse(
          view.id().toString(),
          view.organizationId().toString(),
          view.name(),
          view.status().name(),
          view.initializationStatus().name(),
          view.ownerMemberId().map(Object::toString).orElse(null),
          view.defaultWorkspaceId().map(Object::toString).orElse(null),
          view.version());
    }
  }

  public record TeamMemberResponse(
      String id,
      String userPrincipalId,
      String displayName,
      String status,
      String joinMethod,
      String joinedAt,
      long version) {

    static TeamMemberResponse from(TeamMemberView view) {
      TeamMember member = view.member();
      return new TeamMemberResponse(
          member.id().toString(),
          member.userPrincipalId().toString(),
          view.displayName(),
          member.status().name(),
          member.joinMethod().name(),
          member.joinedAt().map(Object::toString).orElse(null),
          member.version());
    }
  }

  public record WorkspaceResponse(
      String id,
      String organizationId,
      String teamId,
      String type,
      String ownerPrincipalId,
      String name,
      String status,
      long version) {

    static WorkspaceResponse from(Workspace workspace) {
      return new WorkspaceResponse(
          workspace.id().toString(),
          workspace.scope().organizationId().toString(),
          workspace.scope().teamId().map(Object::toString).orElse(null),
          workspace.type().name(),
          workspace.ownerPrincipalId().map(Object::toString).orElse(null),
          workspace.name(),
          workspace.status().name(),
          workspace.version());
    }
  }
}
