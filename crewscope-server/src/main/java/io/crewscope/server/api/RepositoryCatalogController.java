package io.crewscope.server.api;

import io.crewscope.application.coding.RepositoryCatalogApplicationService;
import io.crewscope.application.coding.RepositoryCatalogEntry;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Read-only HTTP boundary for the administrator-only, path-free Repository Catalog. */
@RestController
@RequestMapping(
        "/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}"
                + "/repository-catalog")
public final class RepositoryCatalogController {

    private final RepositoryCatalogApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public RepositoryCatalogController(
            RepositoryCatalogApplicationService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public Mono<ResponseEntity<RepositoryCatalogResponse>> list(
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
                        access -> service.list(access, organization, team, project))
                .map(entries -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(RepositoryCatalogResponse.from(entries)));
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

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    public record RepositoryCatalogResponse(List<RepositoryCatalogItemResponse> items) {

        static RepositoryCatalogResponse from(List<RepositoryCatalogEntry> entries) {
            return new RepositoryCatalogResponse(entries.stream()
                    .map(RepositoryCatalogItemResponse::from)
                    .toList());
        }
    }

    public record RepositoryCatalogItemResponse(
            String repositoryKey, String availability, String suggestedDefaultBranch) {

        static RepositoryCatalogItemResponse from(RepositoryCatalogEntry entry) {
            return new RepositoryCatalogItemResponse(
                    entry.repositoryKey().value(),
                    entry.availability().name(),
                    entry.suggestedDefaultBranch().orElse(null));
        }
    }
}
