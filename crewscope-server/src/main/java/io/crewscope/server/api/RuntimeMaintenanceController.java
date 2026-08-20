package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.runtime.CodingRuntimeMaintenanceOperation;
import io.crewscope.application.runtime.CodingRuntimeMaintenanceOutcome;
import io.crewscope.application.runtime.RuntimeMaintenanceService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.server.config.application.RuntimeObservationProperties;
import io.crewscope.server.observability.RuntimeMaintenanceRecorder;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Platform-admin HTTP boundary for bounded local Coding Runtime maintenance. */
@RestController
public final class RuntimeMaintenanceController {

    private final Optional<RuntimeMaintenanceService> service;
    private final TeamRequestIdentityResolver identityResolver;
    private final RuntimeObservationProperties properties;
    private final RuntimeMaintenanceRecorder recorder;

    public RuntimeMaintenanceController(
            Optional<RuntimeMaintenanceService> service,
            TeamRequestIdentityResolver identityResolver,
            RuntimeObservationProperties properties,
            RuntimeMaintenanceRecorder recorder) {
        this.service = Objects.requireNonNull(service, "service");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @PostMapping("/api/v1/organizations/{organizationId}/runtime-health/operations/reconcile")
    public Mono<ResponseEntity<CommandReceiptResponse>> reconcile(
            @PathVariable String organizationId,
            @RequestParam(required = false) String environment,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            Authentication authentication,
            ServerWebExchange exchange) {
        return command(
                organizationId,
                environment,
                key,
                authentication,
                exchange,
                CodingRuntimeMaintenanceOperation.RECONCILE);
    }

    @PostMapping("/api/v1/organizations/{organizationId}/runtime-health/operations/archive")
    public Mono<ResponseEntity<CommandReceiptResponse>> archive(
            @PathVariable String organizationId,
            @RequestParam(required = false) String environment,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            Authentication authentication,
            ServerWebExchange exchange) {
        return command(
                organizationId,
                environment,
                key,
                authentication,
                exchange,
                CodingRuntimeMaintenanceOperation.ARCHIVE);
    }

    private Mono<ResponseEntity<CommandReceiptResponse>> command(
            String organizationValue,
            String environmentValue,
            String key,
            Authentication authentication,
            ServerWebExchange exchange,
            CodingRuntimeMaintenanceOperation operation) {
        OrganizationId organizationId = organizationId(organizationValue);
        RuntimeEnvironment environment = environment(environmentValue);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, organizationId, correlationId)
                .flatMap(access -> blocking(() -> execute(
                                service.orElseThrow(RuntimeMaintenanceController::unavailable),
                                access,
                                organizationId,
                                environment,
                                idempotencyKey,
                                correlationId,
                                operation))
                        .doOnSuccess(result -> recorder.completed(
                                operation, access, organizationId, correlationId, result))
                        .doOnError(failure -> recorder.failed(
                                operation, access, organizationId, correlationId)))
                .map(CommandReceiptResponse::accepted);
    }

    private static CommandExecution<CodingRuntimeMaintenanceOutcome> execute(
            RuntimeMaintenanceService service,
            TeamAccessContext access,
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            IdempotencyKey idempotencyKey,
            UUID correlationId,
            CodingRuntimeMaintenanceOperation operation) {
        TeamCommandContext context = new TeamCommandContext(
                access, idempotencyKey, correlationId, Optional.empty());
        return operation == CodingRuntimeMaintenanceOperation.RECONCILE
                ? service.reconcile(context, organizationId, environment)
                : service.archive(context, organizationId, environment);
    }

    private RuntimeEnvironment environment(String value) {
        if (value == null) {
            return properties.defaultEnvironment();
        }
        try {
            return new RuntimeEnvironment(value);
        } catch (IllegalArgumentException | DomainValidationException exception) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid Runtime environment",
                    Map.of("field", "environment"));
        }
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request contains an invalid identifier",
                    Map.of("field", "organizationId"));
        }
    }

    private static ApiRequestException unavailable() {
        return new ApiRequestException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "runtime_operations_unavailable",
                "Coding Runtime operations are unavailable on this process",
                Map.of());
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }
}
