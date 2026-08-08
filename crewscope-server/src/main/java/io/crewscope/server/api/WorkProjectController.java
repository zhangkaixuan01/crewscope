package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.workitem.CreateWorkProjectCommand;
import io.crewscope.application.workitem.WorkProjectApplicationService;
import io.crewscope.application.workitem.WorkProjectCursor;
import io.crewscope.application.workitem.WorkProjectKeyAvailability;
import io.crewscope.application.workitem.WorkProjectPage;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

/** HTTP boundary for Team-scoped WorkProject creation, discovery and key validation. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects")
public final class WorkProjectController {

  private final WorkProjectApplicationService service;
  private final TeamRequestIdentityResolver identityResolver;
  private final WorkProjectCursorCodec cursorCodec = new WorkProjectCursorCodec();

  public WorkProjectController(
      WorkProjectApplicationService service, TeamRequestIdentityResolver identityResolver) {
    this.service = service;
    this.identityResolver = identityResolver;
  }

  @PostMapping
  public Mono<ResponseEntity<CommandReceiptResponse>> create(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
      @Valid @RequestBody CreateWorkProjectRequest request,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
    return command(
        authentication,
        organization,
        idempotencyKey,
        exchange,
        context ->
            service.create(
                context, team, new CreateWorkProjectCommand(request.key(), request.name())));
  }

  @GetMapping
  public Mono<ResponseEntity<WorkProjectPageResponse>> list(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    Optional<WorkProjectCursor> cursor =
        Optional.ofNullable(after).map(cursorCodec::decode);
    int pageSize = ApiPagination.limit(limit);
    return query(
            authentication,
            organization,
            exchange,
            access -> service.list(access, organization, team, cursor, pageSize))
        .map(
            page ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(WorkProjectPageResponse.from(page, cursorCodec)));
  }

  @GetMapping("/{projectId}")
  public Mono<ResponseEntity<WorkProjectResponse>> get(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectId,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectId project = projectId(projectId);
    return query(
            authentication,
            organization,
            exchange,
            access -> service.get(access, organization, team, project))
        .map(
            value ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .eTag(Long.toString(value.version()))
                    .body(WorkProjectResponse.from(value)));
  }

  @GetMapping("/keys/{projectKey}")
  public Mono<ResponseEntity<WorkProjectKeyResponse>> keyAvailability(
      @PathVariable String organizationId,
      @PathVariable String teamId,
      @PathVariable String projectKey,
      Authentication authentication,
      ServerWebExchange exchange) {
    OrganizationId organization = organizationId(organizationId);
    TeamId team = teamId(teamId);
    WorkProjectKey key = projectKey(projectKey);
    return query(
            authentication,
            organization,
            exchange,
            access -> service.keyAvailability(access, organization, team, key))
        .map(
            availability ->
                ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(WorkProjectKeyResponse.from(availability)));
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

  private static WorkProjectKey projectKey(String value) {
    try {
      return new WorkProjectKey(value);
    } catch (IllegalArgumentException | DomainValidationException exception) {
      throw new ApiRequestException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Request contains an invalid WorkProject key",
          Map.of("field", "projectKey"));
    }
  }

  private static ApiRequestException invalidIdentifier(String field) {
    return new ApiRequestException(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "invalid_request",
        "Request contains an invalid identifier",
        Map.of("field", field));
  }

  public record CreateWorkProjectRequest(
      @NotBlank @Pattern(regexp = WorkProjectKey.FORMAT_REGEX) String key,
      @NotBlank @Size(max = WorkProject.MAX_NAME_LENGTH) String name) {}

  public record WorkProjectPageResponse(
      List<WorkProjectResponse> items, String nextCursor) {

    static WorkProjectPageResponse from(
        WorkProjectPage page, WorkProjectCursorCodec cursorCodec) {
      return new WorkProjectPageResponse(
          page.items().stream().map(WorkProjectResponse::from).toList(),
          page.nextCursor().map(cursorCodec::encode).orElse(null));
    }
  }

  public record WorkProjectResponse(
      String id,
      String organizationId,
      String teamId,
      String workspaceId,
      String key,
      String name,
      String status,
      long version,
      String createdAt,
      String createdByPrincipalId,
      String updatedAt,
      String updatedByPrincipalId) {

    static WorkProjectResponse from(WorkProject project) {
      return new WorkProjectResponse(
          project.id().toString(),
          project.scope().organizationId().toString(),
          project.scope().teamId().toString(),
          project.scope().workspaceId().toString(),
          project.key().value(),
          project.name(),
          project.status().name(),
          project.version(),
          project.audit().createdAt().toString(),
          project.audit().createdBy().map(Object::toString).orElse(null),
          project.audit().updatedAt().toString(),
          project.audit().updatedBy().map(Object::toString).orElse(null));
    }
  }

  public record WorkProjectKeyResponse(String key, boolean available) {

    static WorkProjectKeyResponse from(WorkProjectKeyAvailability availability) {
      return new WorkProjectKeyResponse(availability.key().value(), availability.available());
    }
  }
}
