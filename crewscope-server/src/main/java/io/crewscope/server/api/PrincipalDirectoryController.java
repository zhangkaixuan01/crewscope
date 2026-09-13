package io.crewscope.server.api;

import io.crewscope.application.principal.PrincipalDirectoryEntry;
import io.crewscope.application.principal.PrincipalDirectoryPage;
import io.crewscope.application.principal.PrincipalDirectoryQuery;
import io.crewscope.application.principal.PrincipalDirectoryQueryService;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Member-safe USER and AGENT directory for replacing UUID input fields. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}")
public final class PrincipalDirectoryController {
    private final PrincipalDirectoryQueryService service;
    private final TeamRequestIdentityResolver identityResolver;

    public PrincipalDirectoryController(
            PrincipalDirectoryQueryService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/principals")
    public Mono<ResponseEntity<PrincipalDirectoryPageResponse>> search(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = parseOrganization(organizationId);
        TeamId team = parseTeam(teamId);
        if (offset < 0) {
            throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request", "offset must be non-negative",
                    java.util.Map.of("parameter", "offset"));
        }
        if (q != null && q.strip().length() > 100) {
            throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request", "q must not exceed 100 characters",
                    java.util.Map.of("parameter", "q"));
        }
        int pageSize = ApiPagination.directoryLimit(limit);
        PrincipalDirectoryQuery query = new PrincipalDirectoryQuery(
                organization, team, Optional.ofNullable(q), offset, pageSize);
        return identityResolver.resolve(authentication, organization,
                        ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> service.search(access, query)))
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(PrincipalDirectoryPageResponse.from(value)));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static OrganizationId parseOrganization(String value) {
        try {
            return OrganizationId.from(value);
        } catch (RuntimeException failure) {
            throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request", "Request contains an invalid Team scope",
                    java.util.Map.of("field", "organizationId"));
        }
    }

    private static TeamId parseTeam(String value) {
        try {
            return TeamId.from(value);
        } catch (RuntimeException failure) {
            throw new ApiRequestException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request", "Request contains an invalid Team scope",
                    java.util.Map.of("field", "teamId"));
        }
    }

    public record PrincipalDirectoryPageResponse(
            List<PrincipalResponse> items, Integer nextOffset) {
        static PrincipalDirectoryPageResponse from(PrincipalDirectoryPage value) {
            return new PrincipalDirectoryPageResponse(value.items().stream()
                    .map(PrincipalResponse::from).toList(),
                    value.nextOffset().isPresent() ? value.nextOffset().getAsInt() : null);
        }
    }

    public record PrincipalResponse(
            String principalId, String kind, String displayName, String status, List<String> roles) {
        static PrincipalResponse from(PrincipalDirectoryEntry value) {
            return new PrincipalResponse(value.principalId().toString(),
                    value.kind().name(), value.displayName(), value.status().name(), value.roles());
        }
    }
}
