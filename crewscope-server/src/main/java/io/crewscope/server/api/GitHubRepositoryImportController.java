package io.crewscope.server.api;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.github.CreateGitHubRepositoryImportCommand;
import io.crewscope.application.github.GitHubRepositoryImportApplicationService;
import io.crewscope.application.github.GitHubRepositoryImportJob;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** HTTP boundary for path-free GitHub Catalog import jobs. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports")
public final class GitHubRepositoryImportController {
    private final GitHubRepositoryImportApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public GitHubRepositoryImportController(GitHubRepositoryImportApplicationService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @PostMapping
    public Mono<ResponseEntity<ImportResponse>> create(
            @PathVariable String organizationId, @PathVariable String teamId, @PathVariable String projectId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotency,
            @Valid @RequestBody ImportRequest request, Authentication authentication, ServerWebExchange exchange) {
        OrganizationId organization = organization(organizationId);
        TeamId team = team(teamId);
        WorkProjectId project = project(projectId);
        IdempotencyKey key = ApiHeaders.requireIdempotencyKey(idempotency);
        return identityResolver.resolve(authentication, organization, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> service.create(
                        new TeamCommandContext(access, key, ApiCorrelationIds.resolve(exchange), java.util.Optional.empty()),
                        organization, team, project, request.command())))
                .map(value -> ResponseEntity.accepted().body(ImportResponse.from(value)));
    }

    @GetMapping("/{jobId}")
    public Mono<ResponseEntity<ImportResponse>> get(
            @PathVariable String organizationId, @PathVariable String teamId, @PathVariable String projectId,
            @PathVariable UUID jobId, Authentication authentication, ServerWebExchange exchange) {
        OrganizationId organization = organization(organizationId); TeamId team = team(teamId); WorkProjectId project = project(projectId);
        return identityResolver.resolve(authentication, organization, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> service.get(access, organization, team, project, jobId)))
                .map(value -> ResponseEntity.ok().body(ImportResponse.from(value)));
    }

    @PostMapping("/{jobId}/cancel")
    public Mono<ResponseEntity<ImportResponse>> cancel(
            @PathVariable String organizationId, @PathVariable String teamId, @PathVariable String projectId,
            @PathVariable UUID jobId, @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotency,
            Authentication authentication, ServerWebExchange exchange) {
        return mutate(organizationId, teamId, projectId, jobId, idempotency, authentication, exchange, true);
    }

    @PostMapping("/{jobId}/retry")
    public Mono<ResponseEntity<ImportResponse>> retry(
            @PathVariable String organizationId, @PathVariable String teamId, @PathVariable String projectId,
            @PathVariable UUID jobId, @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotency,
            Authentication authentication, ServerWebExchange exchange) {
        return mutate(organizationId, teamId, projectId, jobId, idempotency, authentication, exchange, false);
    }

    private Mono<ResponseEntity<ImportResponse>> mutate(String organizationValue, String teamValue, String projectValue,
            UUID jobId, String idempotency, Authentication authentication, ServerWebExchange exchange, boolean cancel) {
        OrganizationId organization = organization(organizationValue); TeamId team = team(teamValue); WorkProjectId project = project(projectValue);
        IdempotencyKey key = ApiHeaders.requireIdempotencyKey(idempotency);
        return identityResolver.resolve(authentication, organization, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> {
                    TeamCommandContext context = new TeamCommandContext(access, key, ApiCorrelationIds.resolve(exchange), java.util.Optional.empty());
                    return cancel ? service.cancel(context, organization, team, project, jobId)
                            : service.retry(context, organization, team, project, jobId);
                })).map(value -> ResponseEntity.ok().body(ImportResponse.from(value)));
    }

    private static <T> Mono<T> blocking(Callable<T> action) { return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic()); }
    private static OrganizationId organization(String value) { try { return OrganizationId.from(value); } catch (RuntimeException e) { throw invalid("organizationId"); } }
    private static TeamId team(String value) { try { return TeamId.from(value); } catch (RuntimeException e) { throw invalid("teamId"); } }
    private static WorkProjectId project(String value) { try { return WorkProjectId.from(value); } catch (RuntimeException e) { throw invalid("projectId"); } }
    private static ApiRequestException invalid(String field) { return new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid_request", "Request contains an invalid import field", Map.of("field", field)); }

    public record ImportRequest(@NotNull UUID connectionId, long connectionVersion, @NotNull UUID grantId,
            long grantVersion, @NotBlank @Size(max = 100) String externalRepositoryId,
            @NotBlank @Size(max = 120) String repositoryKey, @NotBlank @Size(max = 255) String defaultBranch) {
        CreateGitHubRepositoryImportCommand command() {
            return new CreateGitHubRepositoryImportCommand(new ConnectionId(connectionId), connectionVersion,
                    new ConnectionGrantId(grantId), grantVersion, externalRepositoryId,
                    RepositoryKey.parse(repositoryKey), new RepositoryBranchName(defaultBranch));
        }
    }

    public record ImportResponse(String id, String organizationId, String teamId, String projectId,
            String connectionId, long connectionVersion, String externalRepositoryId, String repositoryFullName,
            String repositoryKey, String defaultBranch, String status, int progressPercent, int attempt,
            String failureCode, String bindingId, String createdAt, String updatedAt) {
        static ImportResponse from(GitHubRepositoryImportJob value) {
            return new ImportResponse(value.id().toString(), value.organizationId().toString(), value.teamId().toString(), value.projectId().toString(),
                    value.connectionId().toString(), value.connectionVersion(), value.externalRepositoryId(), value.repositoryFullName(),
                    value.repositoryKey().value(), value.defaultBranch().value(), value.status().name(), value.progressPercent(), value.attempt(),
                    value.failureCode().orElse(null), value.bindingId().map(Object::toString).orElse(null), value.createdAt().toString(), value.updatedAt().toString());
        }
    }
}
