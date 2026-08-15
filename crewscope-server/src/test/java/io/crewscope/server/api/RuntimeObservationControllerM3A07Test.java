package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.runtime.RuntimeCapacitySummary;
import io.crewscope.application.runtime.RuntimeFleetHealth;
import io.crewscope.application.runtime.RuntimeFleetSummary;
import io.crewscope.application.runtime.RuntimeObservationService;
import io.crewscope.application.runtime.RuntimeOperationsView;
import io.crewscope.application.runtime.RuntimeWaitCause;
import io.crewscope.application.runtime.RuntimeWorkerObservation;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.ExecutionRuntimeStatus;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerCapacity;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.runtime.RuntimeWorkerStatus;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.server.config.application.RuntimeObservationProperties;
import io.crewscope.server.observability.RuntimeObservationRecorder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** HTTP, disclosure and correlation contract for M3-A07 Runtime observations. */
class RuntimeObservationControllerM3A07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-15T11:30:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final RuntimeEnvironment environment = new RuntimeEnvironment("development");
    private RuntimeObservationService service;
    private RuntimeObservationRecorder recorder;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeObservationService.class);
        recorder = mock(RuntimeObservationRecorder.class);
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamId),
                PrincipalType.USER,
                Optional.empty(),
                "Member",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        RuntimeObservationProperties properties = new RuntimeObservationProperties();
        client = WebTestClient.bindToController(
                        new RuntimeObservationController(service, resolver, recorder, properties))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void memberSummaryContainsOnlyAggregateHealthCapacityAndWaitingCauses() {
        RuntimeFleetSummary summary = summary(RuntimeFleetHealth.DEGRADED);
        when(service.summary(any(), any(), any(), any())).thenReturn(summary);

        client.get()
                .uri(root())
                .header(ApiCorrelationIds.HEADER, "31aa1c3f-502c-4d90-9b44-32bb2fa398af")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.environment").isEqualTo("development")
                .jsonPath("$.health").isEqualTo("DEGRADED")
                .jsonPath("$.capacity.available").isEqualTo(2)
                .jsonPath("$.waitingCauses[0].cause").isEqualTo("HEARTBEAT_STALE")
                .jsonPath("$.runtimes").doesNotExist()
                .jsonPath("$.workers").doesNotExist()
                .jsonPath("$.stableKey").doesNotExist();

        verify(recorder).record(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void operationsViewExposesAuthorizedRegistryFactsThroughAnExplicitWhitelist() {
        RuntimeFleetSummary summary = summary(RuntimeFleetHealth.HEALTHY);
        ExecutionRuntime runtime = runtime();
        RuntimeWorker worker = worker(runtime);
        when(service.operations(any(), any(), any(), any())).thenReturn(
                new RuntimeOperationsView(
                        summary,
                        List.of(runtime),
                        List.of(new RuntimeWorkerObservation(worker, true, true, true)),
                        List.of()));

        client.get()
                .uri(root() + "/operations?environment=development")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runtimes[0].key").isEqualTo("agentscope-java")
                .jsonPath("$.runtimes[0].implementationVersion").isEqualTo("2.0.0")
                .jsonPath("$.workers[0].stableKey").isEqualTo("worker-a")
                .jsonPath("$.workers[0].health").isEqualTo("HEALTHY")
                .jsonPath("$.workers[0].capabilities.values[0]").isEqualTo("TASK_EXECUTION")
                .jsonPath("$.workers[0].claimToken").doesNotExist()
                .jsonPath("$.workers[0].taskToken").doesNotExist()
                .jsonPath("$.workers[0].configuration").doesNotExist();
    }

    @Test
    void distinguishesAnInactiveRuntimeFromWorkerCapacityExhaustion() {
        RuntimeFleetSummary summary = summary(RuntimeFleetHealth.UNAVAILABLE);
        ExecutionRuntime runtime = runtime();
        RuntimeWorker worker = worker(runtime);
        when(service.operations(any(), any(), any(), any())).thenReturn(
                new RuntimeOperationsView(
                        summary,
                        List.of(runtime),
                        List.of(new RuntimeWorkerObservation(worker, false, true, false)),
                        List.of()));

        client.get()
                .uri(root() + "/operations")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.workers[0].health").isEqualTo("RUNTIME_UNAVAILABLE")
                .jsonPath("$.workers[0].claimable").isEqualTo(false);
    }

    @Test
    void missingOperationsPermissionUsesTheSharedForbiddenEnvelope() {
        when(service.operations(any(), any(), any(), any()))
                .thenThrow(new PolicyDeniedException("observe Runtime operations details"));

        client.get()
                .uri(root() + "/operations")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("policy_denied");
    }

    @Test
    void rejectsInvalidEnvironmentBeforeCallingTheApplicationService() {
        client.get()
                .uri(root() + "?environment=PRODUCTION")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.details.field").isEqualTo("environment");
    }

    private RuntimeFleetSummary summary(RuntimeFleetHealth health) {
        return new RuntimeFleetSummary(
                environment,
                NOW,
                health,
                1,
                1,
                1,
                0,
                0,
                new RuntimeCapacitySummary(4, 2, 2),
                health == RuntimeFleetHealth.DEGRADED ? 1 : 0,
                health == RuntimeFleetHealth.DEGRADED
                        ? Map.of(RuntimeWaitCause.HEARTBEAT_STALE, 1L)
                        : Map.of());
    }

    private ExecutionRuntime runtime() {
        ExecutionRuntime runtime = mock(ExecutionRuntime.class);
        when(runtime.id()).thenReturn(ExecutionRuntimeId.generate());
        when(runtime.key()).thenReturn("agentscope-java");
        when(runtime.displayName()).thenReturn("AgentScope Java");
        when(runtime.implementationVersion()).thenReturn("2.0.0");
        when(runtime.status()).thenReturn(ExecutionRuntimeStatus.ACTIVE);
        when(runtime.capabilities()).thenReturn(
                RuntimeCapabilities.of(RuntimeCapability.TASK_EXECUTION));
        when(runtime.version()).thenReturn(2L);
        when(runtime.audit()).thenReturn(AuditMetadata.createdBy(PrincipalId.generate(), NOW));
        return runtime;
    }

    private RuntimeWorker worker(ExecutionRuntime runtime) {
        RuntimeWorker worker = mock(RuntimeWorker.class);
        when(worker.id()).thenReturn(RuntimeWorkerId.generate());
        ExecutionRuntimeId runtimeId = runtime.id();
        when(worker.runtimeId()).thenReturn(runtimeId);
        when(worker.stableKey()).thenReturn("worker-a");
        when(worker.profile()).thenReturn(RuntimeProfile.ALL);
        when(worker.status()).thenReturn(RuntimeWorkerStatus.ACTIVE);
        when(worker.capabilities()).thenReturn(new RuntimeCapabilities(
                Set.of(RuntimeCapability.TASK_EXECUTION), Set.of("java"), Set.of("maven")));
        when(worker.capacity()).thenReturn(new RuntimeWorkerCapacity(4, 2));
        when(worker.lastHeartbeatAt()).thenReturn(NOW);
        when(worker.heartbeatSequence()).thenReturn(12L);
        when(worker.version()).thenReturn(4L);
        when(worker.audit()).thenReturn(AuditMetadata.createdBy(PrincipalId.generate(), NOW));
        return worker;
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
                + "/runtime-health";
    }
}
