package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.setup.TeamSetupCapability;
import io.crewscope.application.setup.TeamSetupReadinessApplicationService;
import io.crewscope.application.setup.TeamSetupReadinessItem;
import io.crewscope.application.setup.TeamSetupReadinessStatus;
import io.crewscope.application.setup.TeamSetupReadinessView;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.server.config.application.RuntimeObservationProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class TeamSetupReadinessControllerM8A01Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        TeamSetupReadinessApplicationService service = mock(TeamSetupReadinessApplicationService.class);
        TeamAccessContext access = mock(TeamAccessContext.class);
        Principal actor = mock(Principal.class);
        when(access.actor()).thenReturn(actor);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(access);
        TeamSetupReadinessItem item = new TeamSetupReadinessItem(
                TeamSetupCapability.PERSONAL_CONVERSATION,
                true,
                TeamSetupReadinessStatus.ACTION_REQUIRED,
                "PERSONAL_AGENT_CONFIGURATION_REQUIRED",
                true,
                "当前成员",
                Optional.of("OPEN_AGENT_SETTINGS"));
        TeamSetupReadinessView view = new TeamSetupReadinessView(
                organizationId,
                teamId,
                "stable-version",
                UtcTimestamp.parse("2026-09-01T12:00:00Z"),
                List.of(item),
                false);
        when(service.get(any(), any(), any(), any())).thenReturn(view);
        RuntimeObservationProperties properties = new RuntimeObservationProperties();
        client = WebTestClient.bindToController(
                        new TeamSetupReadinessController(service, resolver, properties))
                .build();
    }

    @Test
    void exposesOnlyTheVersionedReadinessContract() {
        client.get()
                .uri("/api/v1/organizations/{organizationId}/teams/{teamId}/setup-readiness",
                        organizationId, teamId)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.organizationId").isEqualTo(organizationId.toString())
                .jsonPath("$.teamId").isEqualTo(teamId.toString())
                .jsonPath("$.snapshotVersion").isEqualTo("stable-version")
                .jsonPath("$.requiredReady").isEqualTo(false)
                .jsonPath("$.capabilities[0].status").isEqualTo("ACTION_REQUIRED")
                .jsonPath("$.capabilities[0].actionKey").isEqualTo("OPEN_AGENT_SETTINGS")
                .jsonPath("$.capabilities[0].endpoint").doesNotExist()
                .jsonPath("$.capabilities[0].secret").doesNotExist();
    }
}
