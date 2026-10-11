package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.operations.OperationsAdministratorDiagnostics;
import io.crewscope.application.operations.OperationsComponentSummary;
import io.crewscope.application.operations.OperationsHealthComponent;
import io.crewscope.application.operations.OperationsHealthLevel;
import io.crewscope.application.operations.OperationsHealthService;
import io.crewscope.application.operations.OperationsMemberHealthSummary;
import io.crewscope.application.operations.OperationsRecoveryCommand;
import io.crewscope.application.operations.OperationsRecoveryResult;
import io.crewscope.application.operations.OperationsRecoveryService;
import io.crewscope.application.operations.OperationsRecoveryStatus;
import io.crewscope.application.observability.ModelUsageRollupService;
import io.crewscope.application.projection.ProjectionAdministrationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Characterization tests for the M11-E01 operations boundary refactor: the HTTP-level
 * branches the M6-A06 suite leaves open — member rejection on the team health endpoint,
 * malformed route identifiers, and the no-store contract of the diagnostics and
 * recovery responses — are locked here before the nested DTOs move to top-level files.
 */
class OperationsControllerM11E01Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-12T06:00:00Z");

    private OperationsHealthService health;
    private OperationsRecoveryService recovery;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                "Administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        TeamAccessContext access = new TeamAccessContext(actor, true);
        health = mock(OperationsHealthService.class);
        recovery = mock(OperationsRecoveryService.class);
        TeamRequestIdentityResolver identities = mock(TeamRequestIdentityResolver.class);
        when(identities.resolve(any(), eq(ORGANIZATION_ID), any()))
                .thenReturn(Mono.just(access));
        client = WebTestClient.bindToController(new OperationsController(
                health,
                recovery,
                mock(ProjectionAdministrationService.class),
                mock(ModelUsageRollupService.class),
                identities))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void teamHealthRejectsNonMembersBeforeTouchingTheHealthService() {
        TeamRequestIdentityResolver identities = mock(TeamRequestIdentityResolver.class);
        when(identities.resolve(any(), eq(ORGANIZATION_ID), any()))
                .thenReturn(Mono.error(new PolicyDeniedException(
                        "view the Team operations health summary")));
        client = WebTestClient.bindToController(new OperationsController(
                health,
                recovery,
                mock(ProjectionAdministrationService.class),
                mock(ModelUsageRollupService.class),
                identities))
                .controllerAdvice(new ApiExceptionHandler())
                .build();

        client.get()
                .uri(teamRoute("/health"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("policy_denied");

        verify(health, never()).summary(any(), any(), any());
    }

    @Test
    void malformedRouteIdentifiersMapToInvalidRequestWithoutServiceCalls() {
        client.get()
                .uri("/api/v1/organizations/not-a-uuid/operations/diagnostics")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.details.field").isEqualTo("organizationId");

        client.get()
                .uri("/api/v1/organizations/" + ORGANIZATION_ID
                        + "/teams/not-a-uuid/operations/health")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.details.field").isEqualTo("organizationId/teamId");

        client.post()
                .uri("/api/v1/organizations/not-a-uuid/operations"
                        + "/model-usage-rollup/rebuilds")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.details.field").isEqualTo("organizationId");

        verify(health, never()).diagnostics(any(), any());
        verify(health, never()).summary(any(), any(), any());
    }

    @Test
    void diagnosticsAndRecoveryResponsesAreNeverCached() {
        OperationsAdministratorDiagnostics diagnostics =
                new OperationsAdministratorDiagnostics(
                        memberSummary(), List.of(), List.of());
        when(health.diagnostics(any(), any())).thenReturn(diagnostics);

        client.get()
                .uri(organizationRoute("/diagnostics"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore());

        NotificationDeliveryId deliveryId =
                new NotificationDeliveryId(UUID.randomUUID());
        when(recovery.recover(any())).thenAnswer(invocation -> {
            OperationsRecoveryCommand command = invocation.getArgument(0);
            return new OperationsRecoveryResult(
                    command.target().action(),
                    command.target().referenceHash(),
                    OperationsRecoveryStatus.SCHEDULED,
                    NOW);
        });

        client.post()
                .uri(organizationRoute("/recoveries"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "no-store-recovery")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "target": {
                            "type": "NOTIFICATION_DELIVERY",
                            "deliveryId": "%s",
                            "expectedVersion": 6
                          },
                          "confirmation": "CONFIRM_RETRY_NOTIFICATION_DELIVERY:%s:6"
                        }
                        """.formatted(deliveryId, deliveryId))
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().cacheControl(CacheControl.noStore());
    }

    private static OperationsMemberHealthSummary memberSummary() {
        return new OperationsMemberHealthSummary(
                NOW,
                OperationsHealthLevel.HEALTHY,
                Arrays.stream(OperationsHealthComponent.values())
                        .map(component -> new OperationsComponentSummary(
                                component, OperationsHealthLevel.HEALTHY, 0, 0, 0, 0, 0, false))
                        .toList());
    }

    private static String teamRoute(String suffix) {
        return "/api/v1/organizations/" + ORGANIZATION_ID + "/teams/" + TEAM_ID
                + "/operations" + suffix;
    }

    private static String organizationRoute(String suffix) {
        return "/api/v1/organizations/" + ORGANIZATION_ID + "/operations" + suffix;
    }
}
