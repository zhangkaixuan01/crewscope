package io.crewscope.server.api;

import io.crewscope.application.runtime.RuntimeCapacitySummary;
import io.crewscope.application.runtime.RuntimeFleetSummary;
import io.crewscope.application.runtime.RuntimeObservationService;
import io.crewscope.application.runtime.RuntimeOperationsView;
import io.crewscope.application.runtime.RuntimeWaitingDiagnostic;
import io.crewscope.application.runtime.RuntimeWorkerObservation;
import io.crewscope.application.runtime.CodingCleanupSummary;
import io.crewscope.application.runtime.CodingRuntimeComponentSummary;
import io.crewscope.application.runtime.CodingRuntimeSnapshot;
import io.crewscope.application.runtime.CodingWorkspaceFleetSummary;
import io.crewscope.application.runtime.ActionDeliveryFleetSummary;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerStatus;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.server.config.application.RuntimeObservationProperties;
import io.crewscope.server.observability.RuntimeObservationRecorder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Member-safe and permission-gated HTTP views of Runtime fleet health and waiting causes. */
@RestController
public final class RuntimeObservationController {

    private final RuntimeObservationService service;
    private final TeamRequestIdentityResolver identityResolver;
    private final RuntimeObservationRecorder recorder;
    private final RuntimeObservationProperties properties;

    public RuntimeObservationController(
            RuntimeObservationService service,
            TeamRequestIdentityResolver identityResolver,
            RuntimeObservationRecorder recorder,
            RuntimeObservationProperties properties) {
        this.service = service;
        this.identityResolver = identityResolver;
        this.recorder = recorder;
        this.properties = properties;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/runtime-health")
    public Mono<ResponseEntity<RuntimeFleetSummaryResponse>> summary(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String environment,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        RuntimeEnvironment selectedEnvironment = environment(environment);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> new AuthorizedResult<>(
                        access,
                        service.summary(
                                access,
                                route.organizationId(),
                                route.teamId(),
                                selectedEnvironment))))
                .map(result -> {
                    recorder.record(
                            RuntimeObservationRecorder.View.MEMBER,
                            result.access(),
                            route.organizationId(),
                            route.teamId(),
                            correlationId,
                            result.value());
                    return noStore(RuntimeFleetSummaryResponse.from(result.value()));
                });
    }

    @GetMapping(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/runtime-health/operations")
    public Mono<ResponseEntity<RuntimeOperationsResponse>> operations(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String environment,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId);
        RuntimeEnvironment selectedEnvironment = environment(environment);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> new AuthorizedResult<>(
                        access,
                        service.operations(
                                access,
                                route.organizationId(),
                                route.teamId(),
                                selectedEnvironment))))
                .map(result -> {
                    recorder.record(
                            RuntimeObservationRecorder.View.OPERATIONS,
                            result.access(),
                            route.organizationId(),
                            route.teamId(),
                            correlationId,
                            result.value().summary());
                    return noStore(RuntimeOperationsResponse.from(result.value()));
                });
    }

    private RuntimeEnvironment environment(String value) {
        if (value == null) {
            return properties.defaultEnvironment();
        }
        try {
            return new RuntimeEnvironment(value);
        } catch (IllegalArgumentException | DomainValidationException exception) {
            throw invalidEnvironment();
        }
    }

    private static Route route(String organizationId, String teamId) {
        try {
            return new Route(OrganizationId.from(organizationId), TeamId.from(teamId));
        } catch (IllegalArgumentException exception) {
            throw new ApiRequestException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid identifier",
                    Map.of("field", "route"));
        }
    }

    private static ApiRequestException invalidEnvironment() {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid Runtime environment",
                Map.of("field", "environment"));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static List<String> sortedCapabilities(
            java.util.Set<? extends Enum<?>> capabilities) {
        return capabilities.stream().map(Enum::name).sorted().toList();
    }

    private static List<String> sortedStrings(java.util.Set<String> values) {
        return values.stream().sorted().toList();
    }

    private record Route(OrganizationId organizationId, TeamId teamId) {}

    private record AuthorizedResult<T>(TeamAccessContext access, T value) {}

    public record RuntimeFleetSummaryResponse(
            String environment,
            Instant observedAt,
            String health,
            int runtimeCount,
            int workerCount,
            int activeWorkerCount,
            int staleWorkerCount,
            int drainingWorkerCount,
            RuntimeCapacityResponse capacity,
            int waitingRuntimeExecutions,
            List<RuntimeWaitCauseResponse> waitingCauses,
            CodingWorkspaceFleetResponse codingWorkspaces,
            ActionDeliveryFleetResponse actionDelivery) {

        static RuntimeFleetSummaryResponse from(RuntimeFleetSummary value) {
            return new RuntimeFleetSummaryResponse(
                    value.environment().value(),
                    value.observedAt().value(),
                    value.health().name(),
                    value.runtimeCount(),
                    value.workerCount(),
                    value.activeWorkerCount(),
                    value.staleWorkerCount(),
                    value.drainingWorkerCount(),
                    RuntimeCapacityResponse.from(value.capacity()),
                    value.waitingRuntimeExecutions(),
                    value.waitingCauses().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> new RuntimeWaitCauseResponse(
                                    entry.getKey().name(), entry.getValue()))
                            .toList(),
                    value.codingWorkspaces()
                            .map(CodingWorkspaceFleetResponse::from)
                            .orElse(null),
                    value.actionDelivery()
                            .map(ActionDeliveryFleetResponse::from)
                            .orElse(null));
        }
    }

    /** Low-cardinality queue state; no Action, Worker, Lease or Provider identity is exposed. */
    public record ActionDeliveryFleetResponse(
            String health,
            long running,
            long unknown,
            long reconciling,
            long manualReview,
            long oldestUnresolvedAgeSeconds,
            boolean stale) {

        static ActionDeliveryFleetResponse from(ActionDeliveryFleetSummary value) {
            return new ActionDeliveryFleetResponse(
                    value.health(),
                    value.running(),
                    value.unknown(),
                    value.reconciling(),
                    value.manualReview(),
                    value.oldestUnresolvedAgeSeconds(),
                    value.stale());
        }
    }

    public record RuntimeCapacityResponse(int maximum, int active, int available) {

        static RuntimeCapacityResponse from(RuntimeCapacitySummary value) {
            return new RuntimeCapacityResponse(
                    value.maximum(), value.active(), value.available());
        }
    }

    public record RuntimeWaitCauseResponse(String cause, long count) {}

    public record CodingWorkspaceFleetResponse(
            String health,
            RuntimeCapacityResponse capacity,
            CodingRuntimeComponentResponse sandboxes,
            CodingRuntimeComponentResponse watchers,
            String cleanupHealth,
            boolean cleanupCapacityLimited) {

        static CodingWorkspaceFleetResponse from(CodingWorkspaceFleetSummary value) {
            return new CodingWorkspaceFleetResponse(
                    value.health().name(),
                    RuntimeCapacityResponse.from(value.capacity()),
                    CodingRuntimeComponentResponse.from(value.sandboxes()),
                    CodingRuntimeComponentResponse.from(value.watchers()),
                    value.cleanupHealth().name(),
                    value.cleanupCapacityLimited());
        }
    }

    public record CodingRuntimeComponentResponse(
            String health, int total, int healthy, int failed) {

        static CodingRuntimeComponentResponse from(CodingRuntimeComponentSummary value) {
            return new CodingRuntimeComponentResponse(
                    value.health().name(), value.total(), value.healthy(), value.failed());
        }
    }

    public record RuntimeOperationsResponse(
            RuntimeFleetSummaryResponse summary,
            List<ExecutionRuntimeResponse> runtimes,
            List<RuntimeWorkerResponse> workers,
            List<RuntimeWaitingExecutionResponse> waitingExecutions,
            CodingRuntimeOperationsResponse codingRuntime) {

        static RuntimeOperationsResponse from(RuntimeOperationsView value) {
            return new RuntimeOperationsResponse(
                    RuntimeFleetSummaryResponse.from(value.summary()),
                    value.runtimes().stream().map(ExecutionRuntimeResponse::from).toList(),
                    value.workers().stream().map(RuntimeWorkerResponse::from).toList(),
                    value.waitingExecutions().stream()
                            .map(RuntimeWaitingExecutionResponse::from)
                            .toList(),
                    value.codingRuntime()
                            .map(CodingRuntimeOperationsResponse::from)
                            .orElse(null));
        }
    }

    public record CodingRuntimeOperationsResponse(
            String health,
            Instant observedAt,
            RuntimeCapacityResponse workspaceCapacity,
            CodingRuntimeComponentResponse sandboxes,
            CodingRuntimeComponentResponse watchers,
            CodingCleanupResponse cleanup) {

        static CodingRuntimeOperationsResponse from(CodingRuntimeSnapshot value) {
            return new CodingRuntimeOperationsResponse(
                    value.health().name(),
                    value.observedAt().value(),
                    RuntimeCapacityResponse.from(value.workspaceCapacity()),
                    CodingRuntimeComponentResponse.from(value.sandboxes()),
                    CodingRuntimeComponentResponse.from(value.watchers()),
                    CodingCleanupResponse.from(value.cleanup()));
        }
    }

    public record CodingCleanupResponse(
            String health,
            boolean completed,
            int recoveredWorkspaces,
            int failedWorkspaces,
            int archivedWorkspaces,
            int archiveFailures,
            int removedSandboxOrphans,
            int purgedArtifacts,
            boolean capacityLimited,
            String lastFailureType) {

        static CodingCleanupResponse from(CodingCleanupSummary value) {
            return new CodingCleanupResponse(
                    value.health().name(),
                    value.completed(),
                    value.recoveredWorkspaces(),
                    value.failedWorkspaces(),
                    value.archivedWorkspaces(),
                    value.archiveFailures(),
                    value.removedSandboxOrphans(),
                    value.purgedArtifacts(),
                    value.capacityLimited(),
                    value.lastFailureType().orElse("NONE"));
        }
    }

    public record ExecutionRuntimeResponse(
            UUID id,
            String key,
            String displayName,
            String implementationVersion,
            String status,
            RuntimeCapabilitiesResponse capabilities,
            long version,
            RuntimeAuditResponse audit) {

        static ExecutionRuntimeResponse from(ExecutionRuntime value) {
            return new ExecutionRuntimeResponse(
                    value.id().value(),
                    value.key(),
                    value.displayName(),
                    value.implementationVersion(),
                    value.status().name(),
                    RuntimeCapabilitiesResponse.from(value.capabilities()),
                    value.version(),
                    RuntimeAuditResponse.from(value.audit()));
        }
    }

    public record RuntimeWorkerResponse(
            UUID id,
            UUID runtimeId,
            String stableKey,
            String profile,
            String status,
            String health,
            boolean heartbeatFresh,
            boolean claimable,
            RuntimeCapabilitiesResponse capabilities,
            RuntimeCapacityResponse capacity,
            Instant lastHeartbeatAt,
            long heartbeatSequence,
            long version,
            RuntimeAuditResponse audit) {

        static RuntimeWorkerResponse from(RuntimeWorkerObservation value) {
            var worker = value.worker();
            return new RuntimeWorkerResponse(
                    worker.id().value(),
                    worker.runtimeId().value(),
                    worker.stableKey(),
                    worker.profile().name(),
                    worker.status().name(),
                    health(value),
                    value.heartbeatFresh(),
                    value.claimable(),
                    RuntimeCapabilitiesResponse.from(worker.capabilities()),
                    new RuntimeCapacityResponse(
                            worker.capacity().maxConcurrentExecutions(),
                            worker.capacity().activeExecutions(),
                            worker.capacity().availableExecutions()),
                    worker.lastHeartbeatAt().value(),
                    worker.heartbeatSequence(),
                    worker.version(),
                    RuntimeAuditResponse.from(worker.audit()));
        }

        private static String health(RuntimeWorkerObservation value) {
            RuntimeWorkerStatus status = value.worker().status();
            if (status != RuntimeWorkerStatus.ACTIVE) {
                return status.name();
            }
            if (!value.heartbeatFresh()) {
                return "STALE";
            }
            if (!value.runtimeActive()) {
                return "RUNTIME_UNAVAILABLE";
            }
            return value.claimable() ? "HEALTHY" : "CAPACITY_EXHAUSTED";
        }
    }

    public record RuntimeCapabilitiesResponse(
            List<String> values, List<String> languages, List<String> buildSystems) {

        static RuntimeCapabilitiesResponse from(RuntimeCapabilities value) {
            return new RuntimeCapabilitiesResponse(
                    sortedCapabilities(value.values()),
                    sortedStrings(value.languages()),
                    sortedStrings(value.buildSystems()));
        }
    }

    public record RuntimeWaitingExecutionResponse(
            UUID taskId,
            UUID executionId,
            int attempt,
            Instant waitingSince,
            String cause,
            RuntimeCapabilitiesResponse requiredCapabilities) {

        static RuntimeWaitingExecutionResponse from(RuntimeWaitingDiagnostic value) {
            var waiting = value.waitingExecution();
            return new RuntimeWaitingExecutionResponse(
                    waiting.execution().taskId().value(),
                    waiting.execution().id().value(),
                    waiting.execution().attempt(),
                    waiting.execution().waiting().orElseThrow().waitingSince().value(),
                    value.cause().name(),
                    RuntimeCapabilitiesResponse.from(waiting.requiredCapabilities()));
        }
    }

    public record RuntimeAuditResponse(
            UUID createdByPrincipalId,
            Instant createdAt,
            UUID updatedByPrincipalId,
            Instant updatedAt) {

        static RuntimeAuditResponse from(AuditMetadata value) {
            return new RuntimeAuditResponse(
                    value.createdBy().map(id -> id.value()).orElse(null),
                    value.createdAt().value(),
                    value.updatedBy().map(id -> id.value()).orElse(null),
                    value.updatedAt().value());
        }
    }
}
