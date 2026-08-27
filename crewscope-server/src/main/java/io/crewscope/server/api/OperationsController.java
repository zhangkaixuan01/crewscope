package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.operations.NotificationDeliveryRecoveryTarget;
import io.crewscope.application.operations.OperationsAdministratorDiagnostics;
import io.crewscope.application.operations.OperationsComponentSummary;
import io.crewscope.application.operations.OperationsHealthService;
import io.crewscope.application.operations.OperationsMemberHealthSummary;
import io.crewscope.application.operations.OperationsRecoveryCommand;
import io.crewscope.application.operations.OperationsRecoveryCommandId;
import io.crewscope.application.operations.OperationsRecoveryResult;
import io.crewscope.application.operations.OperationsRecoveryService;
import io.crewscope.application.operations.OperationsRecoveryStrongConfirmation;
import io.crewscope.application.operations.OperationsRecoveryTarget;
import io.crewscope.application.operations.OutboxDeadLetterRecoveryTarget;
import io.crewscope.application.operations.ProjectionDeadLetterRecoveryTarget;
import io.crewscope.application.operations.ProjectionHealthDiagnostic;
import io.crewscope.application.projection.ProjectionAdministrationAction;
import io.crewscope.application.projection.ProjectionAdministrationCommandId;
import io.crewscope.application.projection.ProjectionAdministrationResult;
import io.crewscope.application.projection.ProjectionAdministrationService;
import io.crewscope.application.projection.ProjectionStrongConfirmation;
import io.crewscope.application.projection.RetryProjectionRebuildCommand;
import io.crewscope.application.projection.StartProjectionRebuildCommand;
import io.crewscope.application.projection.SwitchProjectionGenerationCommand;
import io.crewscope.application.projection.TerminateProjectionRebuildCommand;
import io.crewscope.application.projection.ValidateProjectionGenerationCommand;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.projection.ProjectionDeadLetterId;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionFailureCode;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Safe HTTP boundary for member health and strongly confirmed administrator operations. */
@RestController
public final class OperationsController {

    private static final String ORGANIZATION_ROUTE =
            "/api/v1/organizations/{organizationId}/operations";
    private static final String TEAM_ROUTE =
            "/api/v1/organizations/{organizationId}/teams/{teamId}/operations";

    private final OperationsHealthService health;
    private final OperationsRecoveryService recovery;
    private final ProjectionAdministrationService projections;
    private final TeamRequestIdentityResolver identities;

    public OperationsController(
            OperationsHealthService health,
            OperationsRecoveryService recovery,
            ProjectionAdministrationService projections,
            TeamRequestIdentityResolver identities) {
        this.health = Objects.requireNonNull(health, "health");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    /** Every current Team member receives only the five identifier-free component summaries. */
    @GetMapping(TEAM_ROUTE + "/health")
    public Mono<ResponseEntity<HealthSummaryResponse>> health(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            Authentication authentication,
            ServerWebExchange exchange) {
        TeamRoute route = teamRoute(organizationId, teamId);
        return resolve(authentication, route.organizationId(), exchange)
                .flatMap(access -> blocking(() -> health.summary(
                        access, route.organizationId(), route.teamId())))
                .map(value -> ok(HealthSummaryResponse.from(value)));
    }

    /** Organization administrators receive safe recovery coordinates without raw payloads. */
    @GetMapping(ORGANIZATION_ROUTE + "/diagnostics")
    public Mono<ResponseEntity<AdministratorDiagnosticsResponse>> diagnostics(
            @PathVariable String organizationId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        return resolve(authentication, organization, exchange)
                .flatMap(access -> blocking(() -> health.diagnostics(
                        organization, access)))
                .map(value -> ok(AdministratorDiagnosticsResponse.from(value)));
    }

    /** Replays exactly one diagnostics-provided Outbox, Projection or Notification target. */
    @PostMapping(ORGANIZATION_ROUTE + "/recoveries")
    public Mono<ResponseEntity<RecoveryResponse>> recover(
            @PathVariable String organizationId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestBody RecoveryRequest body,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        OperationsRecoveryTarget target = requireBody(body, "body").target().toDomain();
        OperationsRecoveryCommandId commandId = new OperationsRecoveryCommandId(
                commandUuid("operations-recovery", organization, key));
        if (!OperationsRecoveryStrongConfirmation.expectedPhrase(target)
                .equals(body.confirmation())) {
            throw invalid("confirmation");
        }
        OperationsRecoveryStrongConfirmation confirmation =
                new OperationsRecoveryStrongConfirmation(
                        target.action(), target.referenceHash(), body.confirmation());
        return resolve(authentication, organization, exchange)
                .flatMap(access -> blocking(() -> recovery.recover(new OperationsRecoveryCommand(
                        commandId, organization, target, access, confirmation))))
                .map(result -> ResponseEntity.accepted()
                        .cacheControl(CacheControl.noStore())
                        .body(RecoveryResponse.from(commandId, result)));
    }

    @PostMapping(ORGANIZATION_ROUTE + "/projections/{projectionName}/rebuilds")
    public Mono<ResponseEntity<ProjectionCommandResponse>> startRebuild(
            @PathVariable String organizationId,
            @PathVariable String projectionName,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestBody StartRebuildRequest body,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ProjectionName name = projectionName(projectionName);
        StartRebuildRequest request = requireBody(body, "body");
        ProjectionAdministrationCommandId commandId = projectionCommandId(organization, key);
        return projectionCommand(authentication, organization, exchange, access -> {
            ProjectionAdministrationResult result = projections.start(
                    new StartProjectionRebuildCommand(
                            commandId,
                            organization,
                            name,
                            definitionVersion(request.expectedDefinitionVersion()),
                            version(request.expectedPointerVersion(), "expectedPointerVersion"),
                            access,
                            confirmation(
                                    ProjectionAdministrationAction.START_REBUILD,
                                    name,
                                    Optional.empty(),
                                    request.confirmation())));
            return ProjectionCommandResponse.from(commandId, result, 0, 0);
        });
    }

    @PostMapping(ORGANIZATION_ROUTE
            + "/projections/{projectionName}/rebuilds/{rebuildJobId}/retry")
    public Mono<ResponseEntity<ProjectionCommandResponse>> retryRebuild(
            @PathVariable String organizationId,
            @PathVariable String projectionName,
            @PathVariable String rebuildJobId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestBody RetryRebuildRequest body,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ProjectionName name = projectionName(projectionName);
        ProjectionRebuildJobId previousJob = rebuildJobId(rebuildJobId);
        RetryRebuildRequest request = requireBody(body, "body");
        ProjectionAdministrationCommandId commandId = projectionCommandId(organization, key);
        return projectionCommand(authentication, organization, exchange, access -> {
            ProjectionAdministrationResult result = projections.retry(
                    new RetryProjectionRebuildCommand(
                            commandId,
                            organization,
                            name,
                            previousJob,
                            version(
                                    request.expectedRetryOfJobVersion(),
                                    "expectedRetryOfJobVersion"),
                            definitionVersion(request.expectedDefinitionVersion()),
                            version(request.expectedPointerVersion(), "expectedPointerVersion"),
                            access,
                            confirmation(
                                    ProjectionAdministrationAction.RETRY_REBUILD,
                                    name,
                                    Optional.empty(),
                                    request.confirmation())));
            return ProjectionCommandResponse.from(commandId, result, 0, 0);
        });
    }

    @PostMapping(ORGANIZATION_ROUTE
            + "/projections/{projectionName}/generations/{generation}/validate")
    public Mono<ResponseEntity<ProjectionCommandResponse>> validateGeneration(
            @PathVariable String organizationId,
            @PathVariable String projectionName,
            @PathVariable String generation,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestBody ValidateGenerationRequest body,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ProjectionName name = projectionName(projectionName);
        ProjectionGeneration targetGeneration = generation(generation);
        ValidateGenerationRequest request = requireBody(body, "body");
        long expectedGeneration = version(
                request.expectedGenerationVersion(), "expectedGenerationVersion");
        long expectedJob = version(request.expectedJobVersion(), "expectedJobVersion");
        ProjectionAdministrationCommandId commandId = projectionCommandId(organization, key);
        return projectionCommand(authentication, organization, exchange, access -> {
            ProjectionAdministrationResult result = projections.validate(
                    new ValidateProjectionGenerationCommand(
                            commandId,
                            organization,
                            name,
                            definitionVersion(request.expectedDefinitionVersion()),
                            targetGeneration,
                            rebuildJobId(request.rebuildJobId()),
                            expectedGeneration,
                            expectedJob,
                            access,
                            confirmation(
                                    ProjectionAdministrationAction.VALIDATE_GENERATION,
                                    name,
                                    Optional.of(targetGeneration),
                                    request.confirmation())));
            return ProjectionCommandResponse.from(
                    commandId,
                    result,
                    nextVersion(expectedGeneration),
                    nextVersion(expectedJob));
        });
    }

    @PostMapping(ORGANIZATION_ROUTE
            + "/projections/{projectionName}/generations/{generation}/switch")
    public Mono<ResponseEntity<ProjectionCommandResponse>> switchGeneration(
            @PathVariable String organizationId,
            @PathVariable String projectionName,
            @PathVariable String generation,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestBody SwitchGenerationRequest body,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ProjectionName name = projectionName(projectionName);
        ProjectionGeneration targetGeneration = generation(generation);
        SwitchGenerationRequest request = requireBody(body, "body");
        long expectedTarget = version(
                request.expectedTargetGenerationVersion(),
                "expectedTargetGenerationVersion");
        long expectedJob = version(request.expectedJobVersion(), "expectedJobVersion");
        ProjectionAdministrationCommandId commandId = projectionCommandId(organization, key);
        return projectionCommand(authentication, organization, exchange, access -> {
            ProjectionAdministrationResult result = projections.switchGeneration(
                    new SwitchProjectionGenerationCommand(
                            commandId,
                            organization,
                            name,
                            definitionVersion(request.expectedDefinitionVersion()),
                            generation(request.previousActiveGeneration()),
                            targetGeneration,
                            rebuildJobId(request.rebuildJobId()),
                            version(request.expectedPointerVersion(), "expectedPointerVersion"),
                            version(
                                    request.expectedPreviousGenerationVersion(),
                                    "expectedPreviousGenerationVersion"),
                            expectedTarget,
                            expectedJob,
                            access,
                            confirmation(
                                    ProjectionAdministrationAction.SWITCH_GENERATION,
                                    name,
                                    Optional.of(targetGeneration),
                                    request.confirmation())));
            return ProjectionCommandResponse.from(
                    commandId, result, nextVersion(expectedTarget), nextVersion(expectedJob));
        });
    }

    @PostMapping(ORGANIZATION_ROUTE
            + "/projections/{projectionName}/generations/{generation}"
            + "/rebuilds/{rebuildJobId}/cancel")
    public Mono<ResponseEntity<ProjectionCommandResponse>> cancelRebuild(
            @PathVariable String organizationId,
            @PathVariable String projectionName,
            @PathVariable String generation,
            @PathVariable String rebuildJobId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestBody TerminateRebuildRequest body,
            Authentication authentication,
            ServerWebExchange exchange) {
        return terminate(
                organizationId,
                projectionName,
                generation,
                rebuildJobId,
                key,
                requireBody(body, "body"),
                ProjectionAdministrationAction.CANCEL_REBUILD,
                Optional.empty(),
                authentication,
                exchange);
    }

    @PostMapping(ORGANIZATION_ROUTE
            + "/projections/{projectionName}/generations/{generation}"
            + "/rebuilds/{rebuildJobId}/fail")
    public Mono<ResponseEntity<ProjectionCommandResponse>> failRebuild(
            @PathVariable String organizationId,
            @PathVariable String projectionName,
            @PathVariable String generation,
            @PathVariable String rebuildJobId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestBody FailRebuildRequest body,
            Authentication authentication,
            ServerWebExchange exchange) {
        FailRebuildRequest request = requireBody(body, "body");
        return terminate(
                organizationId,
                projectionName,
                generation,
                rebuildJobId,
                key,
                request,
                ProjectionAdministrationAction.FAIL_REBUILD,
                Optional.of(failureCode(request.failureCode())),
                authentication,
                exchange);
    }

    private Mono<ResponseEntity<ProjectionCommandResponse>> terminate(
            String organizationValue,
            String projectionValue,
            String generationValue,
            String rebuildJobValue,
            String key,
            TerminateRequest request,
            ProjectionAdministrationAction action,
            Optional<ProjectionFailureCode> failureCode,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationValue);
        ProjectionName name = projectionName(projectionValue);
        ProjectionGeneration targetGeneration = generation(generationValue);
        ProjectionRebuildJobId rebuildJob = rebuildJobId(rebuildJobValue);
        long expectedGeneration = version(
                request.expectedGenerationVersion(), "expectedGenerationVersion");
        long expectedJob = version(request.expectedJobVersion(), "expectedJobVersion");
        ProjectionAdministrationCommandId commandId = projectionCommandId(organization, key);
        return projectionCommand(authentication, organization, exchange, access -> {
            ProjectionAdministrationResult result = projections.terminate(
                    new TerminateProjectionRebuildCommand(
                            commandId,
                            organization,
                            name,
                            targetGeneration,
                            rebuildJob,
                            expectedGeneration,
                            expectedJob,
                            action,
                            failureCode,
                            access,
                            confirmation(
                                    action,
                                    name,
                                    Optional.of(targetGeneration),
                                    request.confirmation())));
            return ProjectionCommandResponse.from(
                    commandId,
                    result,
                    nextVersion(expectedGeneration),
                    nextVersion(expectedJob));
        });
    }

    private Mono<ResponseEntity<ProjectionCommandResponse>> projectionCommand(
            Authentication authentication,
            OrganizationId organizationId,
            ServerWebExchange exchange,
            Function<TeamAccessContext, ProjectionCommandResponse> action) {
        return resolve(authentication, organizationId, exchange)
                .flatMap(access -> blocking(() -> action.apply(access)))
                .map(OperationsController::ok);
    }

    private Mono<TeamAccessContext> resolve(
            Authentication authentication,
            OrganizationId organizationId,
            ServerWebExchange exchange) {
        return identities.resolve(
                authentication, organizationId, ApiCorrelationIds.resolve(exchange));
    }

    private static ProjectionAdministrationCommandId projectionCommandId(
            OrganizationId organizationId, String key) {
        return new ProjectionAdministrationCommandId(
                commandUuid("projection-administration", organizationId, key));
    }

    private static UUID commandUuid(
            String namespace, OrganizationId organizationId, String key) {
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        String canonical = "io.crewscope/" + namespace + "/v1/"
                + organizationId + "/" + idempotencyKey.value();
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static ProjectionStrongConfirmation confirmation(
            ProjectionAdministrationAction action,
            ProjectionName projectionName,
            Optional<ProjectionGeneration> generation,
            String phrase) {
        try {
            return new ProjectionStrongConfirmation(
                    action, projectionName, generation, requireText(phrase, "confirmation"));
        } catch (IllegalArgumentException failure) {
            throw invalid("confirmation");
        }
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (RuntimeException failure) {
            throw invalid("organizationId");
        }
    }

    private static TeamRoute teamRoute(String organization, String team) {
        try {
            return new TeamRoute(OrganizationId.from(organization), TeamId.from(team));
        } catch (RuntimeException failure) {
            throw invalid("organizationId/teamId");
        }
    }

    private static ProjectionName projectionName(String value) {
        try {
            return new ProjectionName(value);
        } catch (RuntimeException failure) {
            throw invalid("projectionName");
        }
    }

    private static ProjectionGeneration generation(String value) {
        try {
            return new ProjectionGeneration(Long.parseLong(value));
        } catch (RuntimeException failure) {
            throw invalid("generation");
        }
    }

    private static ProjectionGeneration generation(Long value) {
        if (value == null) {
            throw invalid("previousActiveGeneration");
        }
        try {
            return new ProjectionGeneration(value);
        } catch (RuntimeException failure) {
            throw invalid("previousActiveGeneration");
        }
    }

    private static ProjectionDefinitionVersion definitionVersion(Long value) {
        if (value == null) {
            throw invalid("expectedDefinitionVersion");
        }
        try {
            return new ProjectionDefinitionVersion(value);
        } catch (RuntimeException failure) {
            throw invalid("expectedDefinitionVersion");
        }
    }

    private static ProjectionRebuildJobId rebuildJobId(String value) {
        try {
            return new ProjectionRebuildJobId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw invalid("rebuildJobId");
        }
    }

    private static ProjectionFailureCode failureCode(String value) {
        try {
            return new ProjectionFailureCode(value);
        } catch (RuntimeException failure) {
            throw invalid("failureCode");
        }
    }

    private static long version(Long value, String field) {
        if (value == null || value < 0) {
            throw invalid(field);
        }
        return value;
    }

    private static long nextVersion(long value) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException failure) {
            throw new IllegalStateException("Projection version is exhausted", failure);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field);
        }
        return value.strip();
    }

    private static <T> T requireBody(T value, String field) {
        if (value == null) {
            throw invalid(field);
        }
        return value;
    }

    private static ApiRequestException invalid(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid operations command field",
                Map.of("field", field));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private record TeamRoute(OrganizationId organizationId, TeamId teamId) {}

    public record ComponentHealthResponse(
            String component,
            String health,
            long backlog,
            long inFlight,
            long failures,
            long affected,
            long oldestOutstandingAgeSeconds,
            boolean stale) {

        static ComponentHealthResponse from(OperationsComponentSummary value) {
            return new ComponentHealthResponse(
                    value.component().name(),
                    value.health().name(),
                    value.backlog(),
                    value.inFlight(),
                    value.failures(),
                    value.affected(),
                    value.oldestOutstandingAgeSeconds(),
                    value.stale());
        }
    }

    public record HealthSummaryResponse(
            String observedAt, String health, List<ComponentHealthResponse> components) {

        static HealthSummaryResponse from(OperationsMemberHealthSummary value) {
            return new HealthSummaryResponse(
                    value.observedAt().toString(),
                    value.health().name(),
                    value.components().stream().map(ComponentHealthResponse::from).toList());
        }
    }

    public record ProjectionDiagnosticResponse(
            String projectionName,
            long definitionVersion,
            long activeGeneration,
            long pointerVersion,
            long activeGenerationVersion,
            Optional<Long> shadowGeneration,
            Optional<String> shadowStatus,
            Optional<Long> shadowGenerationVersion,
            Optional<String> rebuildJobId,
            Optional<Long> rebuildJobVersion,
            long lagSeconds,
            long gapCount,
            long deadLetterCount,
            Optional<String> latestFailureCode,
            String startConfirmation,
            Optional<String> validateConfirmation,
            Optional<String> switchConfirmation,
            Optional<String> cancelConfirmation,
            Optional<String> failConfirmation) {

        static ProjectionDiagnosticResponse from(ProjectionHealthDiagnostic value) {
            Optional<ProjectionGeneration> shadow = value.shadowGeneration();
            return new ProjectionDiagnosticResponse(
                    value.projectionName().value(),
                    value.definitionVersion().value(),
                    value.activeGeneration().value(),
                    value.pointerVersion(),
                    value.activeGenerationVersion(),
                    shadow.map(ProjectionGeneration::value),
                    value.shadowStatus().map(Enum::name),
                    value.shadowGenerationVersion().isPresent()
                            ? Optional.of(value.shadowGenerationVersion().getAsLong())
                            : Optional.empty(),
                    value.rebuildJobId().map(valueId -> valueId.value().toString()),
                    value.rebuildJobVersion().isPresent()
                            ? Optional.of(value.rebuildJobVersion().getAsLong())
                            : Optional.empty(),
                    value.lagSeconds(),
                    value.gapCount(),
                    value.deadLetterCount(),
                    value.latestFailureCode().map(ProjectionFailureCode::value),
                    ProjectionStrongConfirmation.expectedPhrase(
                            ProjectionAdministrationAction.START_REBUILD,
                            value.projectionName(),
                            Optional.empty()),
                    phrase(value, ProjectionAdministrationAction.VALIDATE_GENERATION),
                    phrase(value, ProjectionAdministrationAction.SWITCH_GENERATION),
                    phrase(value, ProjectionAdministrationAction.CANCEL_REBUILD),
                    phrase(value, ProjectionAdministrationAction.FAIL_REBUILD));
        }

        private static Optional<String> phrase(
                ProjectionHealthDiagnostic value, ProjectionAdministrationAction action) {
            return value.shadowGeneration().map(generation ->
                    ProjectionStrongConfirmation.expectedPhrase(
                            action, value.projectionName(), Optional.of(generation)));
        }
    }

    public sealed interface RecoveryCandidateResponse permits
            OutboxRecoveryCandidateResponse,
            ProjectionRecoveryCandidateResponse,
            NotificationRecoveryCandidateResponse {}

    public record OutboxRecoveryCandidateResponse(
            String type,
            String action,
            String outboxEventId,
            String domainEventId,
            long expectedVersion,
            String referenceHash,
            String confirmation) implements RecoveryCandidateResponse {}

    public record ProjectionRecoveryCandidateResponse(
            String type,
            String action,
            String projectionName,
            long generation,
            String deadLetterId,
            String domainEventId,
            long expectedGenerationVersion,
            String referenceHash,
            String confirmation) implements RecoveryCandidateResponse {}

    public record NotificationRecoveryCandidateResponse(
            String type,
            String action,
            String deliveryId,
            long expectedVersion,
            String referenceHash,
            String confirmation) implements RecoveryCandidateResponse {}

    public record AdministratorDiagnosticsResponse(
            HealthSummaryResponse summary,
            List<ProjectionDiagnosticResponse> projections,
            List<RecoveryCandidateResponse> recoveryCandidates) {

        static AdministratorDiagnosticsResponse from(OperationsAdministratorDiagnostics value) {
            return new AdministratorDiagnosticsResponse(
                    HealthSummaryResponse.from(value.summary()),
                    value.projections().stream().map(ProjectionDiagnosticResponse::from).toList(),
                    value.recoveryCandidates().stream()
                            .map(AdministratorDiagnosticsResponse::candidate)
                            .toList());
        }

        private static RecoveryCandidateResponse candidate(OperationsRecoveryTarget value) {
            String confirmation = OperationsRecoveryStrongConfirmation.expectedPhrase(value);
            if (value instanceof OutboxDeadLetterRecoveryTarget target) {
                return new OutboxRecoveryCandidateResponse(
                        "OUTBOX_DEAD_LETTER",
                        target.action().name(),
                        target.outboxEventId().toString(),
                        target.domainEventId().toString(),
                        target.expectedVersion(),
                        target.referenceHash(),
                        confirmation);
            }
            if (value instanceof ProjectionDeadLetterRecoveryTarget target) {
                return new ProjectionRecoveryCandidateResponse(
                        "PROJECTION_DEAD_LETTER",
                        target.action().name(),
                        target.projectionName().value(),
                        target.generation().value(),
                        target.deadLetterId().value().toString(),
                        target.domainEventId().toString(),
                        target.expectedGenerationVersion(),
                        target.referenceHash(),
                        confirmation);
            }
            if (value instanceof NotificationDeliveryRecoveryTarget target) {
                return new NotificationRecoveryCandidateResponse(
                        "NOTIFICATION_DELIVERY",
                        target.action().name(),
                        target.deliveryId().toString(),
                        target.expectedVersion(),
                        target.referenceHash(),
                        confirmation);
            }
            throw new IllegalStateException("Unsupported operations recovery target");
        }
    }

    public record RecoveryResponse(
            String commandId,
            String action,
            String targetReferenceHash,
            String status,
            String acceptedAt) {

        static RecoveryResponse from(
                OperationsRecoveryCommandId commandId, OperationsRecoveryResult value) {
            return new RecoveryResponse(
                    commandId.value().toString(),
                    value.action().name(),
                    value.targetReferenceHash(),
                    value.status().name(),
                    value.acceptedAt().toString());
        }
    }

    public record ProjectionCommandResponse(
            String commandId,
            String projectionName,
            long generation,
            String rebuildJobId,
            String generationStatus,
            String rebuildStatus,
            long generationVersion,
            long rebuildJobVersion,
            Optional<Long> pointerVersion) {

        static ProjectionCommandResponse from(
                ProjectionAdministrationCommandId commandId,
                ProjectionAdministrationResult value,
                long generationVersion,
                long rebuildJobVersion) {
            return new ProjectionCommandResponse(
                    commandId.value().toString(),
                    value.projectionName().value(),
                    value.generation().value(),
                    value.rebuildJobId().value().toString(),
                    value.generationStatus().name(),
                    value.rebuildStatus().name(),
                    generationVersion,
                    rebuildJobVersion,
                    value.pointerVersion().isPresent()
                            ? Optional.of(value.pointerVersion().getAsLong())
                            : Optional.empty());
        }
    }

    public static final class RecoveryRequest {

        private final RecoveryTargetRequest target;
        private final String confirmation;

        @JsonCreator
        public RecoveryRequest(
                @JsonProperty("target") RecoveryTargetRequest target,
                @JsonProperty("confirmation") String confirmation) {
            this.target = requireBody(target, "target");
            this.confirmation = requireText(confirmation, "confirmation");
        }

        public RecoveryTargetRequest target() {
            return target;
        }

        public String confirmation() {
            return confirmation;
        }

        @JsonAnySetter
        void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported operations recovery property");
        }
    }

    /** Closed union. Fields that do not belong to the selected type are rejected. */
    public static final class RecoveryTargetRequest {

        private final String type;
        private final String outboxEventId;
        private final String projectionName;
        private final Long generation;
        private final String deadLetterId;
        private final String deliveryId;
        private final String domainEventId;
        private final Long expectedVersion;
        private final Long expectedGenerationVersion;

        @JsonCreator
        public RecoveryTargetRequest(
                @JsonProperty("type") String type,
                @JsonProperty("outboxEventId") String outboxEventId,
                @JsonProperty("projectionName") String projectionName,
                @JsonProperty("generation") Long generation,
                @JsonProperty("deadLetterId") String deadLetterId,
                @JsonProperty("deliveryId") String deliveryId,
                @JsonProperty("domainEventId") String domainEventId,
                @JsonProperty("expectedVersion") Long expectedVersion,
                @JsonProperty("expectedGenerationVersion") Long expectedGenerationVersion) {
            this.type = requireText(type, "target.type");
            this.outboxEventId = outboxEventId;
            this.projectionName = projectionName;
            this.generation = generation;
            this.deadLetterId = deadLetterId;
            this.deliveryId = deliveryId;
            this.domainEventId = domainEventId;
            this.expectedVersion = expectedVersion;
            this.expectedGenerationVersion = expectedGenerationVersion;
        }

        OperationsRecoveryTarget toDomain() {
            try {
                return switch (type) {
                    case "OUTBOX_DEAD_LETTER" -> outboxTarget();
                    case "PROJECTION_DEAD_LETTER" -> projectionTarget();
                    case "NOTIFICATION_DELIVERY" -> notificationTarget();
                    default -> throw invalid("target.type");
                };
            } catch (ApiRequestException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw invalid("target");
            }
        }

        private OperationsRecoveryTarget outboxTarget() {
            requireAbsent(projectionName, generation, deadLetterId, deliveryId,
                    expectedGenerationVersion);
            return new OutboxDeadLetterRecoveryTarget(
                    UUID.fromString(requireText(outboxEventId, "target.outboxEventId")),
                    UUID.fromString(requireText(domainEventId, "target.domainEventId")),
                    version(expectedVersion, "target.expectedVersion"));
        }

        private OperationsRecoveryTarget projectionTarget() {
            requireAbsent(outboxEventId, deliveryId, expectedVersion);
            if (generation == null) {
                throw invalid("target.generation");
            }
            return new ProjectionDeadLetterRecoveryTarget(
                    OperationsController.projectionName(projectionName),
                    new ProjectionGeneration(generation),
                    new ProjectionDeadLetterId(
                            UUID.fromString(requireText(deadLetterId, "target.deadLetterId"))),
                    UUID.fromString(requireText(domainEventId, "target.domainEventId")),
                    version(
                            expectedGenerationVersion,
                            "target.expectedGenerationVersion"));
        }

        private OperationsRecoveryTarget notificationTarget() {
            requireAbsent(outboxEventId, projectionName, generation, deadLetterId,
                    domainEventId, expectedGenerationVersion);
            return new NotificationDeliveryRecoveryTarget(
                    new NotificationDeliveryId(
                            UUID.fromString(requireText(deliveryId, "target.deliveryId"))),
                    version(expectedVersion, "target.expectedVersion"));
        }

        private static void requireAbsent(Object... values) {
            for (Object value : values) {
                if (value != null) {
                    throw invalid("target");
                }
            }
        }

        @JsonAnySetter
        void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported operations recovery target property");
        }
    }

    public static final class StartRebuildRequest {

        private final Long expectedDefinitionVersion;
        private final Long expectedPointerVersion;
        private final String confirmation;

        @JsonCreator
        public StartRebuildRequest(
                @JsonProperty("expectedDefinitionVersion") Long expectedDefinitionVersion,
                @JsonProperty("expectedPointerVersion") Long expectedPointerVersion,
                @JsonProperty("confirmation") String confirmation) {
            this.expectedDefinitionVersion = expectedDefinitionVersion;
            this.expectedPointerVersion = expectedPointerVersion;
            this.confirmation = confirmation;
        }

        public Long expectedDefinitionVersion() { return expectedDefinitionVersion; }

        public Long expectedPointerVersion() { return expectedPointerVersion; }

        public String confirmation() { return confirmation; }

        @JsonAnySetter
        void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported Projection rebuild property");
        }
    }

    public static final class RetryRebuildRequest {

        private final Long expectedRetryOfJobVersion;
        private final Long expectedDefinitionVersion;
        private final Long expectedPointerVersion;
        private final String confirmation;

        @JsonCreator
        public RetryRebuildRequest(
                @JsonProperty("expectedRetryOfJobVersion") Long expectedRetryOfJobVersion,
                @JsonProperty("expectedDefinitionVersion") Long expectedDefinitionVersion,
                @JsonProperty("expectedPointerVersion") Long expectedPointerVersion,
                @JsonProperty("confirmation") String confirmation) {
            this.expectedRetryOfJobVersion = expectedRetryOfJobVersion;
            this.expectedDefinitionVersion = expectedDefinitionVersion;
            this.expectedPointerVersion = expectedPointerVersion;
            this.confirmation = confirmation;
        }

        public Long expectedRetryOfJobVersion() { return expectedRetryOfJobVersion; }

        public Long expectedDefinitionVersion() { return expectedDefinitionVersion; }

        public Long expectedPointerVersion() { return expectedPointerVersion; }

        public String confirmation() { return confirmation; }

        @JsonAnySetter
        void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported Projection retry property");
        }
    }

    public static final class ValidateGenerationRequest {

        private final Long expectedDefinitionVersion;
        private final String rebuildJobId;
        private final Long expectedGenerationVersion;
        private final Long expectedJobVersion;
        private final String confirmation;

        @JsonCreator
        public ValidateGenerationRequest(
                @JsonProperty("expectedDefinitionVersion") Long expectedDefinitionVersion,
                @JsonProperty("rebuildJobId") String rebuildJobId,
                @JsonProperty("expectedGenerationVersion") Long expectedGenerationVersion,
                @JsonProperty("expectedJobVersion") Long expectedJobVersion,
                @JsonProperty("confirmation") String confirmation) {
            this.expectedDefinitionVersion = expectedDefinitionVersion;
            this.rebuildJobId = rebuildJobId;
            this.expectedGenerationVersion = expectedGenerationVersion;
            this.expectedJobVersion = expectedJobVersion;
            this.confirmation = confirmation;
        }

        public Long expectedDefinitionVersion() { return expectedDefinitionVersion; }

        public String rebuildJobId() { return rebuildJobId; }

        public Long expectedGenerationVersion() { return expectedGenerationVersion; }

        public Long expectedJobVersion() { return expectedJobVersion; }

        public String confirmation() { return confirmation; }

        @JsonAnySetter
        void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported Projection validation property");
        }
    }

    public static final class SwitchGenerationRequest {

        private final Long expectedDefinitionVersion;
        private final Long previousActiveGeneration;
        private final String rebuildJobId;
        private final Long expectedPointerVersion;
        private final Long expectedPreviousGenerationVersion;
        private final Long expectedTargetGenerationVersion;
        private final Long expectedJobVersion;
        private final String confirmation;

        @JsonCreator
        public SwitchGenerationRequest(
                @JsonProperty("expectedDefinitionVersion") Long expectedDefinitionVersion,
                @JsonProperty("previousActiveGeneration") Long previousActiveGeneration,
                @JsonProperty("rebuildJobId") String rebuildJobId,
                @JsonProperty("expectedPointerVersion") Long expectedPointerVersion,
                @JsonProperty("expectedPreviousGenerationVersion")
                        Long expectedPreviousGenerationVersion,
                @JsonProperty("expectedTargetGenerationVersion")
                        Long expectedTargetGenerationVersion,
                @JsonProperty("expectedJobVersion") Long expectedJobVersion,
                @JsonProperty("confirmation") String confirmation) {
            this.expectedDefinitionVersion = expectedDefinitionVersion;
            this.previousActiveGeneration = previousActiveGeneration;
            this.rebuildJobId = rebuildJobId;
            this.expectedPointerVersion = expectedPointerVersion;
            this.expectedPreviousGenerationVersion = expectedPreviousGenerationVersion;
            this.expectedTargetGenerationVersion = expectedTargetGenerationVersion;
            this.expectedJobVersion = expectedJobVersion;
            this.confirmation = confirmation;
        }

        public Long expectedDefinitionVersion() { return expectedDefinitionVersion; }

        public Long previousActiveGeneration() { return previousActiveGeneration; }

        public String rebuildJobId() { return rebuildJobId; }

        public Long expectedPointerVersion() { return expectedPointerVersion; }

        public Long expectedPreviousGenerationVersion() {
            return expectedPreviousGenerationVersion;
        }

        public Long expectedTargetGenerationVersion() { return expectedTargetGenerationVersion; }

        public Long expectedJobVersion() { return expectedJobVersion; }

        public String confirmation() { return confirmation; }

        @JsonAnySetter
        void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported Projection switch property");
        }
    }

    private interface TerminateRequest {
        Long expectedGenerationVersion();

        Long expectedJobVersion();

        String confirmation();
    }

    public static final class TerminateRebuildRequest implements TerminateRequest {

        private final Long expectedGenerationVersion;
        private final Long expectedJobVersion;
        private final String confirmation;

        @JsonCreator
        public TerminateRebuildRequest(
                @JsonProperty("expectedGenerationVersion") Long expectedGenerationVersion,
                @JsonProperty("expectedJobVersion") Long expectedJobVersion,
                @JsonProperty("confirmation") String confirmation) {
            this.expectedGenerationVersion = expectedGenerationVersion;
            this.expectedJobVersion = expectedJobVersion;
            this.confirmation = confirmation;
        }

        @Override
        public Long expectedGenerationVersion() { return expectedGenerationVersion; }

        @Override
        public Long expectedJobVersion() { return expectedJobVersion; }

        @Override
        public String confirmation() { return confirmation; }

        @JsonAnySetter
        void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported Projection cancellation property");
        }
    }

    public static final class FailRebuildRequest implements TerminateRequest {

        private final Long expectedGenerationVersion;
        private final Long expectedJobVersion;
        private final String failureCode;
        private final String confirmation;

        @JsonCreator
        public FailRebuildRequest(
                @JsonProperty("expectedGenerationVersion") Long expectedGenerationVersion,
                @JsonProperty("expectedJobVersion") Long expectedJobVersion,
                @JsonProperty("failureCode") String failureCode,
                @JsonProperty("confirmation") String confirmation) {
            this.expectedGenerationVersion = expectedGenerationVersion;
            this.expectedJobVersion = expectedJobVersion;
            this.failureCode = failureCode;
            this.confirmation = confirmation;
        }

        @Override
        public Long expectedGenerationVersion() { return expectedGenerationVersion; }

        @Override
        public Long expectedJobVersion() { return expectedJobVersion; }

        public String failureCode() { return failureCode; }

        @Override
        public String confirmation() { return confirmation; }

        @JsonAnySetter
        void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
            throw new IllegalArgumentException("Unsupported Projection failure property");
        }
    }
}
