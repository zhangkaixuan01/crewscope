package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.operations.OperationsHealthService;
import io.crewscope.application.operations.OperationsRecoveryService;
import io.crewscope.application.observability.ModelUsageRollupService;
import io.crewscope.application.projection.ProjectionAdministrationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Acceptance tests for the F03a usage-rollup rebuild boundary (M10-F03): the endpoint
 * is platform-administrator only, answers no-store with the projected fact count, and
 * a non-administrator principal never reaches the rebuild service.
 */
class OperationsControllerM10F03Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-05T06:00:00Z");

    private ModelUsageRollupService rollups;
    private WebTestClient client;
    private TeamAccessContext access;

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
        access = new TeamAccessContext(actor, true);
        rollups = mock(ModelUsageRollupService.class);
        TeamRequestIdentityResolver identities = mock(TeamRequestIdentityResolver.class);
        when(identities.resolve(any(), any(), any())).thenReturn(Mono.just(access));
        client = WebTestClient.bindToController(new OperationsController(
                mock(OperationsHealthService.class),
                mock(OperationsRecoveryService.class),
                mock(ProjectionAdministrationService.class),
                rollups,
                identities))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void platformAdministratorsRebuildSynchronouslyWithNoStore() {
        when(rollups.rebuildAll()).thenReturn(42L);

        client.post()
                .uri("/api/v1/organizations/{organizationId}/operations"
                                + "/model-usage-rollup/rebuilds",
                        ORGANIZATION_ID.value().toString())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED")
                .jsonPath("$.projectedFacts").isEqualTo(42);

        verify(rollups).rebuildAll();
    }

    @Test
    void nonAdministratorsNeverReachTheRebuildService() {
        access = new TeamAccessContext(access.actor(), false);
        TeamRequestIdentityResolver identities = mock(TeamRequestIdentityResolver.class);
        when(identities.resolve(any(), any(), any())).thenReturn(Mono.just(access));
        client = WebTestClient.bindToController(new OperationsController(
                mock(OperationsHealthService.class),
                mock(OperationsRecoveryService.class),
                mock(ProjectionAdministrationService.class),
                rollups,
                identities))
                .controllerAdvice(new ApiExceptionHandler())
                .build();

        client.post()
                .uri("/api/v1/organizations/{organizationId}/operations"
                                + "/model-usage-rollup/rebuilds",
                        ORGANIZATION_ID.value().toString())
                .exchange()
                .expectStatus().isForbidden();

        verify(rollups, never()).rebuildAll();
    }
}
