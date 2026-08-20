package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.runtime.CodingCleanupSummary;
import io.crewscope.application.runtime.CodingRuntimeComponentHealth;
import io.crewscope.application.runtime.CodingRuntimeComponentSummary;
import io.crewscope.application.runtime.CodingRuntimeMaintenanceOperation;
import io.crewscope.application.runtime.CodingRuntimeMaintenanceOutcome;
import io.crewscope.application.runtime.CodingRuntimeSnapshot;
import io.crewscope.application.runtime.CodingWorkspaceFleetSummary;
import io.crewscope.application.runtime.RuntimeCapacitySummary;
import io.crewscope.application.runtime.RuntimeFleetHealth;
import io.crewscope.application.runtime.RuntimeFleetSummary;
import io.crewscope.application.runtime.RuntimeMaintenanceService;
import io.crewscope.application.runtime.RuntimeObservationService;
import io.crewscope.application.runtime.RuntimeOperationsView;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.server.config.application.RuntimeObservationProperties;
import io.crewscope.server.observability.RuntimeMaintenanceRecorder;
import io.crewscope.server.observability.RuntimeObservationRecorder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** HTTP disclosure, idempotency and availability contract for M4-A07 operations. */
class RuntimeOperationsControllerM4A07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-20T06:30:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final RuntimeEnvironment environment = new RuntimeEnvironment("development");
    private final RuntimeObservationService observations = mock(RuntimeObservationService.class);
    private final RuntimeMaintenanceService maintenance = mock(RuntimeMaintenanceService.class);
    private final RuntimeObservationRecorder observationRecorder = mock(RuntimeObservationRecorder.class);
    private final RuntimeMaintenanceRecorder maintenanceRecorder = mock(RuntimeMaintenanceRecorder.class);
    private TeamRequestIdentityResolver identityResolver;
    private RuntimeObservationProperties properties;

    @BeforeEach
    void setUp() {
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamId),
                PrincipalType.USER,
                Optional.empty(),
                "Administrator",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
        identityResolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, true));
        properties = new RuntimeObservationProperties();
    }

    @Test
    void memberAndOperationsViewsUseExplicitPathFreeCodingWhitelists() {
        CodingRuntimeSnapshot coding = codingSnapshot();
        RuntimeFleetSummary summary = summary(coding);
        when(observations.summary(any(), any(), any(), any())).thenReturn(summary);
        when(observations.operations(any(), any(), any(), any())).thenReturn(
                new RuntimeOperationsView(
                        summary, List.of(), List.of(), List.of(), Optional.of(coding)));
        WebTestClient client = WebTestClient.bindToController(new RuntimeObservationController(
                        observations, identityResolver, observationRecorder, properties))
                .controllerAdvice(new ApiExceptionHandler())
                .build();

        client.get()
                .uri(teamRoot())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.codingWorkspaces.capacity.available").isEqualTo(3)
                .jsonPath("$.codingWorkspaces.watchers.health").isEqualTo("DEGRADED")
                .jsonPath("$.codingWorkspaces.lastFailureType").doesNotExist()
                .jsonPath("$.containerId").doesNotExist()
                .jsonPath("$.hostPath").doesNotExist();

        client.get()
                .uri(teamRoot() + "/operations")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.codingRuntime.cleanup.lastFailureType")
                        .isEqualTo("WorkspaceDiffException")
                .jsonPath("$.codingRuntime.workspaceId").doesNotExist()
                .jsonPath("$.codingRuntime.containerName").doesNotExist()
                .jsonPath("$.codingRuntime.storageUri").doesNotExist();
    }

    @Test
    void reconcileReturnsTheSharedReplayReceiptContract() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        when(maintenance.reconcile(any(), any(), any()))
                .thenReturn(CommandExecution.replayed(receipt));
        WebTestClient client = maintenanceClient(Optional.of(maintenance));

        client.post()
                .uri(organizationRoot() + "/reconcile")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m4-a07-reconcile")
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
                .expectBody()
                .jsonPath("$.commandId").isEqualTo(receipt.commandId().toString());
    }

    @Test
    void serverOnlyProcessReturnsStableUnavailableEnvelope() {
        maintenanceClient(Optional.empty())
                .post()
                .uri(organizationRoot() + "/archive")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m4-a07-archive")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("runtime_operations_unavailable");
    }

    @Test
    void resolvesIdentityBeforeDisclosingServerOnlyRuntimeCapability() {
        identityResolver = (authentication, organization, correlationId) -> Mono.error(
                new ApiRequestException(
                        HttpStatus.UNAUTHORIZED,
                        "authentication_required",
                        "Authentication is required",
                        Map.of()));

        maintenanceClient(Optional.empty())
                .post()
                .uri(organizationRoot() + "/archive")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m4-a07-unauthenticated")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("authentication_required");
    }

    private WebTestClient maintenanceClient(Optional<RuntimeMaintenanceService> service) {
        return WebTestClient.bindToController(new RuntimeMaintenanceController(
                        service, identityResolver, properties, maintenanceRecorder))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private RuntimeFleetSummary summary(CodingRuntimeSnapshot coding) {
        return new RuntimeFleetSummary(
                environment,
                NOW,
                RuntimeFleetHealth.DEGRADED,
                1,
                1,
                1,
                0,
                0,
                new RuntimeCapacitySummary(4, 1, 3),
                0,
                Map.of(),
                Optional.of(CodingWorkspaceFleetSummary.from(coding)));
    }

    private CodingRuntimeSnapshot codingSnapshot() {
        return new CodingRuntimeSnapshot(
                organizationId,
                environment,
                NOW,
                CodingRuntimeComponentHealth.DEGRADED,
                new RuntimeCapacitySummary(4, 1, 3),
                new CodingRuntimeComponentSummary(
                        CodingRuntimeComponentHealth.HEALTHY, 1, 1, 0),
                new CodingRuntimeComponentSummary(
                        CodingRuntimeComponentHealth.DEGRADED, 1, 0, 1),
                new CodingCleanupSummary(
                        CodingRuntimeComponentHealth.DEGRADED,
                        true,
                        1,
                        0,
                        2,
                        0,
                        1,
                        3,
                        false,
                        Optional.of("WorkspaceDiffException")));
    }

    private String teamRoot() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
                + "/runtime-health";
    }

    private String organizationRoot() {
        return "/api/v1/organizations/" + organizationId
                + "/runtime-health/operations";
    }
}
