package io.crewscope.server.api;

import io.crewscope.application.retrieval.KnowledgeSwitchBoard;
import io.crewscope.application.retrieval.KnowledgeSwitchQueryService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * M13b-A03 read-only projection of the six deployment knowledge switches. The board is bound
 * from configuration alone (no knowledge tables involved), so this answers the member-facing
 * "is the capability even enabled?" question (first-round review R04) without leaking any
 * environment-variable names — the response is booleans only, member-level, and no-store.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/knowledge")
public final class KnowledgeConfigController {

    private final KnowledgeSwitchQueryService service;
    private final TeamRequestIdentityResolver identityResolver;

    public KnowledgeConfigController(
            KnowledgeSwitchQueryService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/config")
    public Mono<ResponseEntity<KnowledgeConfigResponse>> config(
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
                        access -> service.switches(access, organization, team))
                .map(board -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(KnowledgeConfigResponse.from(board)));
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
        } catch (RuntimeException failure) {
            throw invalidField("organizationId");
        }
    }

    private static TeamId teamId(String value) {
        try {
            return TeamId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("teamId");
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid knowledge config field",
                Map.of("field", field));
    }

    /**
     * Booleans only (contract §5: no environment-variable names surface here) — the
     * member-facing closed-capability copy keys off these fields alone.
     */
    record KnowledgeConfigResponse(
            boolean vectorEnabled,
            boolean indexEnabled,
            boolean indexWorkerEnabled,
            boolean retrievalEnabled,
            boolean injectionEnabled,
            boolean skillEnabled) {

        static KnowledgeConfigResponse from(KnowledgeSwitchBoard board) {
            return new KnowledgeConfigResponse(
                    board.vectorEnabled(),
                    board.indexEnabled(),
                    board.indexWorkerEnabled(),
                    board.retrievalEnabled(),
                    board.injectionEnabled(),
                    board.skillEnabled());
        }
    }
}
