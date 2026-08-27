package io.crewscope.server.api;

import io.crewscope.application.correlation.CorrelationCursor;
import io.crewscope.application.correlation.CorrelationPage;
import io.crewscope.application.correlation.CorrelationQueryService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.ObjectProvider;
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

/** Current-membership HTTP boundary for payload-free Correlation graphs. */
@RestController
@RequestMapping(
        "/api/v1/organizations/{organizationId}/teams/{teamId}/correlations/{correlationId}")
public final class CorrelationController {

    private final CorrelationQueryService service;
    private final TeamRequestIdentityResolver identities;
    private final ObjectProvider<CorrelationCursorCodec> codecs;

    public CorrelationController(
            CorrelationQueryService service,
            TeamRequestIdentityResolver identities,
            ObjectProvider<CorrelationCursorCodec> codecs) {
        this.service = service;
        this.identities = identities;
        this.codecs = codecs;
    }

    @GetMapping
    public Mono<ResponseEntity<CorrelationPageResponse>> find(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String correlationId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, correlationId);
        int pageSize = ApiPagination.limit(limit);
        CorrelationCursorCodec codec = codec();
        UUID requestCorrelation = ApiCorrelationIds.resolve(exchange);
        return identities.resolve(authentication, route.organizationId(), requestCorrelation)
                .flatMap(access -> blocking(() -> page(access, route, after, pageSize, codec)))
                .map(page -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(CorrelationPageResponse.from(page, route.teamId(), codec)));
    }

    private CorrelationPage page(
            TeamAccessContext access,
            Route route,
            String after,
            int limit,
            CorrelationCursorCodec codec) {
        Optional<CorrelationCursor> cursor = Optional.empty();
        if (after != null && !after.isBlank()) {
            // Authorization deliberately precedes decoding to avoid a signed-cursor oracle.
            service.requireAccess(access, route.organizationId(), route.teamId());
            cursor = Optional.of(codec.decode(
                    after, route.organizationId(), route.teamId(), route.correlationId()));
        }
        return service.find(
                access, route.organizationId(), route.teamId(), route.correlationId(), cursor, limit);
    }

    private CorrelationCursorCodec codec() {
        CorrelationCursorCodec codec = codecs.getIfAvailable();
        if (codec == null) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "correlation_unavailable", "Correlation history is unavailable", Map.of());
        }
        return codec;
    }

    private static Route route(String organizationId, String teamId, String correlationId) {
        try {
            return new Route(
                    OrganizationId.from(organizationId), TeamId.from(teamId),
                    UUID.fromString(correlationId));
        } catch (IllegalArgumentException failure) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request", "Request contains an invalid Correlation route",
                    Map.of("field", "path"));
        }
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private record Route(
            OrganizationId organizationId, TeamId teamId, UUID correlationId) {}
}
