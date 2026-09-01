package io.crewscope.server.api;

import io.crewscope.application.setup.TeamSetupCapability;
import io.crewscope.application.setup.TeamSetupReadinessApplicationService;
import io.crewscope.application.setup.TeamSetupReadinessItem;
import io.crewscope.application.setup.TeamSetupReadinessStatus;
import io.crewscope.application.setup.TeamSetupReadinessView;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.server.config.application.RuntimeObservationProperties;
import java.util.List;
import java.util.Map;
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

/** Member-safe HTTP boundary for the immutable Team Setup Readiness snapshot. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}")
public final class TeamSetupReadinessController {

    private final TeamSetupReadinessApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;
    private final RuntimeObservationProperties runtimeProperties;

    public TeamSetupReadinessController(
            TeamSetupReadinessApplicationService service,
            TeamRequestIdentityResolver identityResolver,
            RuntimeObservationProperties runtimeProperties) {
        this.service = service;
        this.identityResolver = identityResolver;
        this.runtimeProperties = runtimeProperties;
    }

    @GetMapping("/setup-readiness")
    public Mono<ResponseEntity<ReadinessResponse>> get(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String environment,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        RuntimeEnvironment selected = environment(environment);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> service.get(
                        access, route.organizationId(), route.teamId(), selected)))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ReadinessResponse.from(value)));
    }

    private RuntimeEnvironment environment(String value) {
        if (value == null || value.isBlank()) {
            return runtimeProperties.defaultEnvironment();
        }
        try {
            return new RuntimeEnvironment(value);
        } catch (IllegalArgumentException | DomainValidationException failure) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid runtime environment",
                    Map.of("field", "environment"));
        }
    }

    private static Route route(String organization, String team) {
        try {
            return new Route(OrganizationId.from(organization), TeamId.from(team));
        } catch (RuntimeException failure) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid Team scope",
                    Map.of("field", "scope"));
        }
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private record Route(OrganizationId organizationId, TeamId teamId) {}

    public record ReadinessResponse(
            String organizationId,
            String teamId,
            String snapshotVersion,
            String observedAt,
            List<CapabilityResponse> capabilities,
            boolean requiredReady) {

        static ReadinessResponse from(TeamSetupReadinessView value) {
            return new ReadinessResponse(
                    value.organizationId().toString(),
                    value.teamId().toString(),
                    value.snapshotVersion(),
                    value.observedAt().toString(),
                    value.capabilities().stream().map(CapabilityResponse::from).toList(),
                    value.requiredReady());
        }
    }

    public record CapabilityResponse(
            String capability,
            boolean required,
            String status,
            String reasonCode,
            boolean canConfigure,
            String responsibleParty,
            Optional<String> actionKey) {

        static CapabilityResponse from(TeamSetupReadinessItem value) {
            return new CapabilityResponse(
                    value.capability().name(),
                    value.required(),
                    value.status().name(),
                    value.reasonCode(),
                    value.canConfigure(),
                    value.responsibleParty(),
                    value.actionKey());
        }
    }
}
