package io.crewscope.server.api;

import io.crewscope.application.setup.TeamSetupCapability;
import io.crewscope.application.setup.ConfigurationHealthApplicationService;
import io.crewscope.application.setup.ConfigurationHealthItem;
import io.crewscope.application.setup.ConfigurationHealthView;
import io.crewscope.application.setup.ConfigurationSearchApplicationService;
import io.crewscope.application.setup.ConfigurationSearchResult;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ConfigurationHealthApplicationService configurationHealth;
    private final ConfigurationSearchApplicationService configurationSearch;

    @Autowired
    public TeamSetupReadinessController(
            TeamSetupReadinessApplicationService service,
            TeamRequestIdentityResolver identityResolver,
            RuntimeObservationProperties runtimeProperties,
            ConfigurationHealthApplicationService configurationHealth,
            ConfigurationSearchApplicationService configurationSearch) {
        this.service = service;
        this.identityResolver = identityResolver;
        this.runtimeProperties = runtimeProperties;
        this.configurationHealth = configurationHealth;
        this.configurationSearch = configurationSearch;
    }

    /** Compatibility constructor for focused readiness controller tests and embedders. */
    public TeamSetupReadinessController(
            TeamSetupReadinessApplicationService service,
            TeamRequestIdentityResolver identityResolver,
            RuntimeObservationProperties runtimeProperties) {
        this(service, identityResolver, runtimeProperties, null, null);
    }

    /** Returns the derived configuration health projection used by the Settings and Setup views. */
    @GetMapping("/configuration-health")
    public Mono<ResponseEntity<ConfigurationHealthResponse>> configurationHealth(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String environment,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        if (configurationHealth == null) {
            throw new IllegalStateException("configuration health is not configured");
        }
        RuntimeEnvironment selected = environment(environment);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> configurationHealth.get(
                        access, route.organizationId(), route.teamId(), selected)))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ConfigurationHealthResponse.from(value)));
    }

    /** Searches visible configuration metadata without returning user-entered values. */
    @GetMapping("/configuration-search")
    public Mono<ResponseEntity<ConfigurationSearchResponse>> configurationSearch(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam String q,
            Authentication authentication,
            ServerWebExchange exchange) {
        if (configurationSearch == null) {
            throw new IllegalStateException("configuration search is not configured");
        }
        Route route = route(organizationId, teamId);
        String term = query(q);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> configurationSearch.search(
                        access, route.organizationId(), route.teamId(), term)))
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(ConfigurationSearchResponse.from(value)));
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

    /**
     * The A06 search contract bounds `q` to 1-100 characters. The application service carries the
     * same precondition, but a bare precondition failure there has no API mapping; validating here
     * keeps a padded or oversized query on the documented `invalid_request` path.
     */
    private static String query(String value) {
        String term = value == null ? "" : value.strip();
        if (term.isEmpty() || term.length() > 100) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid configuration query",
                    Map.of("field", "q"));
        }
        return term;
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

    public record ConfigurationHealthResponse(
            String organizationId,
            String teamId,
            String observedAt,
            String overallStatus,
            List<ConfigurationHealthItemResponse> items) {
        static ConfigurationHealthResponse from(ConfigurationHealthView value) {
            return new ConfigurationHealthResponse(
                    value.organizationId().toString(), value.teamId().toString(),
                    value.observedAt().toString(), value.overallStatus().name(),
                    value.items().stream().map(ConfigurationHealthItemResponse::from).toList());
        }
    }

    public record ConfigurationHealthItemResponse(
            String component,
            String status,
            String reasonCode,
            String responsibleParty,
            Optional<String> actionKey) {
        static ConfigurationHealthItemResponse from(ConfigurationHealthItem value) {
            return new ConfigurationHealthItemResponse(value.component(), value.status().name(),
                    value.reasonCode(), value.responsibleParty(), value.actionKey());
        }
    }

    public record ConfigurationSearchResponse(List<ConfigurationSearchResultResponse> items) {
        static ConfigurationSearchResponse from(List<ConfigurationSearchResult> values) {
            return new ConfigurationSearchResponse(values.stream()
                    .map(ConfigurationSearchResultResponse::from).toList());
        }
    }

    public record ConfigurationSearchResultResponse(
            String profileId, long revision, String field, String label, String route) {
        static ConfigurationSearchResultResponse from(ConfigurationSearchResult value) {
            return new ConfigurationSearchResultResponse(value.profileId(), value.revision(),
                    value.field(), value.label(), value.route());
        }
    }
}
