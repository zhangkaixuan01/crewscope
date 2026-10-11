package io.crewscope.server.api;

import io.crewscope.application.operations.OperationsHealthService;
import io.crewscope.application.operations.OperationsRecoveryCommand;
import io.crewscope.application.operations.OperationsRecoveryCommandId;
import io.crewscope.application.operations.OperationsRecoveryService;
import io.crewscope.application.operations.OperationsRecoveryStrongConfirmation;
import io.crewscope.application.operations.OperationsRecoveryTarget;
import io.crewscope.application.observability.ModelUsageRollupService;
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
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionFailureCode;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
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
    private final ModelUsageRollupService rollups;
    private final TeamRequestIdentityResolver identities;

    public OperationsController(
            OperationsHealthService health,
            OperationsRecoveryService recovery,
            ProjectionAdministrationService projections,
            ModelUsageRollupService rollups,
            TeamRequestIdentityResolver identities) {
        this.health = Objects.requireNonNull(health, "health");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.rollups = Objects.requireNonNull(rollups, "rollups");
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
                OperationsCommandIdentity.recovery(organization, key));
        if (!OperationsRecoveryStrongConfirmation.expectedPhrase(target)
                .equals(body.confirmation())) {
            throw OperationsRequestParser.invalid("confirmation");
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

    /**
     * Recomputes the monthly usage rollup from the canonical event log (M10-F03).
     * Platform administrators only: the delete-and-replay sweep is an operations-plane
     * action, never a Team member surface. Synchronous by design — the rebuild replays
     * every persisted usage fact (the sweep grows with the event log) and returns the
     * projected count. Unlike the neighboring recovery endpoints this needs no
     * confirmation phrase: the rebuild is structurally idempotent, so rerunning it
     * converges to the same table.
     */
    @PostMapping(ORGANIZATION_ROUTE + "/model-usage-rollup/rebuilds")
    public Mono<ResponseEntity<RollupRebuildResponse>> rebuildUsageRollup(
            @PathVariable String organizationId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        return resolve(authentication, organization, exchange)
                .flatMap(access -> blocking(() -> {
                    if (!access.platformAdministrator()) {
                        throw new PolicyDeniedException("rebuild the usage rollup projection");
                    }
                    return RollupRebuildResponse.completed(rollups.rebuildAll());
                }))
                .map(OperationsController::ok);
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
        return OperationsCommandIdentity.projection(organizationId, key);
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
            throw OperationsRequestParser.invalid("confirmation");
        }
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (RuntimeException failure) {
            throw OperationsRequestParser.invalid("organizationId");
        }
    }

    private static TeamRoute teamRoute(String organization, String team) {
        try {
            return new TeamRoute(OrganizationId.from(organization), TeamId.from(team));
        } catch (RuntimeException failure) {
            throw OperationsRequestParser.invalid("organizationId/teamId");
        }
    }

    private static ProjectionName projectionName(String value) {
        return OperationsRequestParser.projectionName(value);
    }

    private static ProjectionGeneration generation(String value) {
        return OperationsRequestParser.generation(value, "generation");
    }

    private static ProjectionGeneration generation(Long value) {
        return OperationsRequestParser.generation(value, "previousActiveGeneration");
    }

    private static ProjectionDefinitionVersion definitionVersion(Long value) {
        return OperationsRequestParser.definitionVersion(value);
    }

    private static ProjectionRebuildJobId rebuildJobId(String value) {
        return OperationsRequestParser.rebuildJobId(value);
    }

    private static ProjectionFailureCode failureCode(String value) {
        return OperationsRequestParser.failureCode(value);
    }

    private static long version(Long value, String field) {
        return OperationsRequestParser.version(value, field);
    }

    private static long nextVersion(long value) {
        return OperationsRequestParser.nextVersion(value);
    }

    private static String requireText(String value, String field) {
        return OperationsRequestParser.requireText(value, field);
    }

    private static <T> T requireBody(T value, String field) {
        return OperationsRequestParser.requireBody(value, field);
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private record TeamRoute(OrganizationId organizationId, TeamId teamId) {}
}
