package io.crewscope.server.api;

import io.crewscope.application.coding.ProjectExecutionDefaultsApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.ProjectExecutionDefaults;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Project-level defaults: select existing managed facts, never arbitrary host commands. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/execution-defaults")
public final class ProjectExecutionDefaultsController {
    private final ProjectExecutionDefaultsApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public ProjectExecutionDefaultsController(
            ProjectExecutionDefaultsApplicationService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public Mono<ResponseEntity<ExecutionDefaultsResponse>> get(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, projectId);
        return identityResolver.resolve(authentication, route.organizationId(), ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> service.get(
                        access, route.organizationId(), route.teamId(), route.projectId())))
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.version()))
                        .body(ExecutionDefaultsResponse.from(value)));
    }

    @PutMapping
    public Mono<ResponseEntity<ExecutionDefaultsResponse>> replace(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody ExecutionDefaultsRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, projectId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        ApiHeaders.requireIdempotencyKey(idempotencyKey);
        Parsed parsed = Parsed.from(request);
        return identityResolver.resolve(authentication, route.organizationId(), ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> service.replace(
                        access, route.organizationId(), route.teamId(), route.projectId(), expectedVersion,
                        parsed.repositoryBindingId(), parsed.repositoryBindingVersion(), parsed.branch(),
                        parsed.buildProfile(), parsed.agentProfileId(), parsed.agentProfileRevision())))
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.version()))
                        .body(ExecutionDefaultsResponse.from(value)));
    }

    @GetMapping("/options")
    public Mono<ResponseEntity<BuildProfileOptionsResponse>> options(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, projectId);
        return identityResolver.resolve(authentication, route.organizationId(), ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> service.listBuildProfiles(
                        access, route.organizationId(), route.teamId(), route.projectId())))
                .map(values -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(BuildProfileOptionsResponse.from(values)));
    }

    private static Route route(String organizationId, String teamId, String projectId) {
        try {
            return new Route(OrganizationId.from(organizationId), TeamId.from(teamId), WorkProjectId.from(projectId));
        } catch (IllegalArgumentException exception) {
            throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request", "Request contains an invalid project identifier", Map.of("field", "route"));
        }
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record ExecutionDefaultsRequest(
            String repositoryBindingId,
            Long repositoryBindingVersion,
            String branch,
            BuildProfileRequest buildProfile,
            String agentProfileId,
            Long agentProfileRevision) {}

    public record BuildProfileRequest(String key, Long version, String profileHash) {}

    public record BuildProfileOptionsResponse(List<BuildProfileRequest> items) {
        static BuildProfileOptionsResponse from(List<io.crewscope.domain.coding.BuildProfile> values) {
            return new BuildProfileOptionsResponse(values.stream()
                    .sorted(Comparator.comparing(io.crewscope.domain.coding.BuildProfile::key)
                            .thenComparingLong(io.crewscope.domain.coding.BuildProfile::version))
                    .map(profile -> new BuildProfileRequest(profile.key(), profile.version(), profile.profileHash().value()))
                    .toList());
        }
    }

    public record DefaultField<T>(T value, String source, String availability, String reason) {}

    public record ExecutionDefaultsResponse(
            long version,
            DefaultField<String> repositoryBindingId,
            DefaultField<Long> repositoryBindingVersion,
            DefaultField<String> branch,
            DefaultField<BuildProfileRequest> buildProfile,
            DefaultField<String> agentProfileId,
            DefaultField<Long> agentProfileRevision) {
        static ExecutionDefaultsResponse from(ProjectExecutionDefaults value) {
            String repoReason = value.repositoryBindingId().isPresent() ? "项目已选择仓库绑定" : "尚未设置项目仓库";
            String repoAvailability = value.repositoryBindingId().isPresent() ? "AVAILABLE" : "MISSING";
            String profileReason = value.buildProfile().isPresent() ? "项目已选择受控构建方案" : "尚未设置构建方案";
            String profileAvailability = value.buildProfile().isPresent() ? "AVAILABLE" : "MISSING";
            String agentReason = value.agentProfileId().isPresent() ? "项目已选择 Agent" : "使用任务/团队解析结果";
            return new ExecutionDefaultsResponse(value.version(),
                    new DefaultField<>(value.repositoryBindingId().map(RepositoryBindingId::toString).orElse(null), "PROJECT_DEFAULT", repoAvailability, repoReason),
                    new DefaultField<>(value.repositoryBindingVersion().orElse(null), "PROJECT_DEFAULT", repoAvailability, repoReason),
                    new DefaultField<>(value.branch().map(RepositoryBranchName::value).orElse(null), "PROJECT_DEFAULT", value.branch().isPresent() ? "AVAILABLE" : "MISSING", value.branch().isPresent() ? "项目默认分支" : "仓库绑定默认分支将被使用"),
                    new DefaultField<>(value.buildProfile().map(reference -> new BuildProfileRequest(reference.key(), reference.version(), reference.profileHash().value())).orElse(null), "PROJECT_DEFAULT", profileAvailability, profileReason),
                    new DefaultField<>(value.agentProfileId().map(AgentProfileId::toString).orElse(null), "PROJECT_DEFAULT", value.agentProfileId().isPresent() ? "AVAILABLE" : "INHERITED", agentReason),
                    new DefaultField<>(value.agentProfileRevision().orElse(null), "PROJECT_DEFAULT", value.agentProfileRevision().isPresent() ? "AVAILABLE" : "INHERITED", agentReason));
        }
    }

    private record Parsed(
            Optional<RepositoryBindingId> repositoryBindingId,
            Optional<Long> repositoryBindingVersion,
            Optional<RepositoryBranchName> branch,
            Optional<BuildProfileReference> buildProfile,
            Optional<AgentProfileId> agentProfileId,
            Optional<Long> agentProfileRevision) {
        static Parsed from(ExecutionDefaultsRequest request) {
            if (request == null) throw invalid("body");
            Optional<RepositoryBindingId> binding = parseUuid(request.repositoryBindingId(), RepositoryBindingId::new, "repositoryBindingId");
            Optional<Long> bindingVersion = Optional.ofNullable(request.repositoryBindingVersion());
            Optional<RepositoryBranchName> branch = Optional.ofNullable(request.branch()).map(value -> new RepositoryBranchName(value));
            if (binding.isEmpty() != bindingVersion.isEmpty()) throw invalid("repositoryBindingVersion");
            Optional<BuildProfileReference> profile = Optional.ofNullable(request.buildProfile()).map(Parsed::profile);
            Optional<AgentProfileId> agent = parseUuid(request.agentProfileId(), AgentProfileId::new, "agentProfileId");
            Optional<Long> agentRevision = Optional.ofNullable(request.agentProfileRevision());
            if (agent.isEmpty() != agentRevision.isEmpty()) throw invalid("agentProfileRevision");
            return new Parsed(binding, bindingVersion, branch, profile, agent, agentRevision);
        }
        private static BuildProfileReference profile(BuildProfileRequest request) {
            if (request.key() == null || request.version() == null || request.profileHash() == null) throw invalid("buildProfile");
            return new BuildProfileReference(request.key(), request.version(), new TaskFactHash(request.profileHash()));
        }
        private static <T> Optional<T> parseUuid(String value, java.util.function.Function<UUID, T> constructor, String field) {
            if (value == null) return Optional.empty();
            try { return Optional.of(constructor.apply(UUID.fromString(value))); }
            catch (IllegalArgumentException exception) { throw invalid(field); }
        }
        private static ApiRequestException invalid(String field) {
            return new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid_request", "Request contains an invalid execution default", Map.of("field", field));
        }
    }

    private record Route(OrganizationId organizationId, TeamId teamId, WorkProjectId projectId) {}
}
