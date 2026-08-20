package io.crewscope.server.api;

import io.crewscope.application.coding.CodingTargetSelectionService;
import io.crewscope.application.coding.RepositoryBindingPreflightResult;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Member-facing discovery and Ref Preflight API for one WorkItem CodingTarget form. */
@RestController
@RequestMapping(
        "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}"
                + "/work-items/{workItemId}/coding-target")
public final class CodingTargetController {

    private final CodingTargetSelectionService service;
    private final TeamRequestIdentityResolver identityResolver;

    public CodingTargetController(
            CodingTargetSelectionService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/build-profiles")
    public Mono<ResponseEntity<BuildProfileListResponse>> listBuildProfiles(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @PathVariable String workItemId,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, projectId, workItemId);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> service.listBuildProfiles(
                        access,
                        route.organizationId(),
                        route.teamId(),
                        route.projectId(),
                        route.workItemId())))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(BuildProfileListResponse.from(values)));
    }

    @PostMapping("/preflight")
    public Mono<ResponseEntity<CodingTargetPreflightResponse>> preflight(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String projectId,
            @PathVariable String workItemId,
            @Valid @RequestBody CodingTargetPreflightRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, projectId, workItemId);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        RepositoryBindingId bindingId = new RepositoryBindingId(request.repositoryBindingId());
        RepositoryBranchName baselineRef = new RepositoryBranchName(request.baselineRef());
        return identityResolver
                .resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> service.preflight(
                        access,
                        route.organizationId(),
                        route.teamId(),
                        route.projectId(),
                        route.workItemId(),
                        bindingId,
                        baselineRef)))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(CodingTargetPreflightResponse.from(value)));
    }

    private static Route route(
            String organizationId, String teamId, String projectId, String workItemId) {
        try {
            return new Route(
                    OrganizationId.from(organizationId),
                    TeamId.from(teamId),
                    WorkProjectId.from(projectId),
                    WorkItemId.from(workItemId));
        } catch (IllegalArgumentException exception) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid identifier",
                    Map.of("route", "coding-target"));
        }
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record CodingTargetPreflightRequest(
            @NotNull UUID repositoryBindingId,
            @NotBlank @Size(max = RepositoryBranchName.MAX_LENGTH) String baselineRef) {}

    public record BuildProfileListResponse(List<BuildProfileResponse> items) {

        static BuildProfileListResponse from(List<BuildProfile> values) {
            return new BuildProfileListResponse(
                    values.stream().map(BuildProfileResponse::from).toList());
        }
    }

    public record BuildProfileResponse(
            String key,
            long version,
            String profileHash,
            String buildTool,
            int javaRelease,
            List<String> commandKinds) {

        static BuildProfileResponse from(BuildProfile profile) {
            List<String> commands = profile.commandCatalog().commands().keySet().stream()
                    .map(Enum::name)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            return new BuildProfileResponse(
                    profile.key(),
                    profile.version(),
                    profile.profileHash().value(),
                    profile.buildTool().name(),
                    profile.javaRelease(),
                    commands);
        }
    }

    public record CodingTargetPreflightResponse(
            boolean ready, String repositoryKey, String baselineRef, String baselineCommit) {

        static CodingTargetPreflightResponse from(RepositoryBindingPreflightResult result) {
            return new CodingTargetPreflightResponse(
                    true,
                    result.repositoryKey().value(),
                    result.baselineRef().value(),
                    result.baselineCommit().value());
        }
    }

    private record Route(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId) {}
}
