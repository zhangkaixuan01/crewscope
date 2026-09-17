package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.setup.ConfigurationHealthApplicationService;
import io.crewscope.application.setup.ConfigurationSearchApplicationService;
import io.crewscope.application.setup.ConfigurationSearchResult;
import io.crewscope.application.setup.TeamSetupReadinessApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.server.config.application.RuntimeObservationProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * The A06 search contract bounds the query to 1-100 characters and reports a violation as
 * `invalid_request`. The application service carries the same precondition, but it is only
 * reachable through this boundary, so the boundary is where the contract is pinned.
 */
class TeamSetupReadinessControllerConfigurationSearchTest {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private ConfigurationSearchApplicationService search;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        search = mock(ConfigurationSearchApplicationService.class);
        TeamAccessContext access = mock(TeamAccessContext.class);
        when(access.actor()).thenReturn(mock(Principal.class));
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(access);
        client = WebTestClient.bindToController(new TeamSetupReadinessController(
                        mock(TeamSetupReadinessApplicationService.class),
                        resolver,
                        new RuntimeObservationProperties(),
                        mock(ConfigurationHealthApplicationService.class),
                        search))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void rejectsABlankQueryWithoutReachingTheService() {
        search("   ")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request");

        verifyNoInteractions(search);
    }

    @Test
    void rejectsAQueryBeyondOneHundredCharactersWithoutReachingTheService() {
        search("模".repeat(101))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request");

        verifyNoInteractions(search);
    }

    @Test
    void returnsOnlyTheMetadataContractForAValidQuery() {
        ConfigurationSearchResult result = new ConfigurationSearchResult(
                UUID.randomUUID().toString(), 2, "modelBinding", "模型绑定",
                "/settings/agents?agent=" + UUID.randomUUID());
        when(search.search(any(), eq(organizationId), eq(teamId), eq("模型")))
                .thenReturn(List.of(result));

        search(" 模型 ")
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items[0].profileId").isEqualTo(result.profileId())
                .jsonPath("$.items[0].field").isEqualTo("modelBinding")
                .jsonPath("$.items[0].label").isEqualTo("模型绑定")
                .jsonPath("$.items[0].value").doesNotExist()
                .jsonPath("$.items[0].prompt").doesNotExist();
    }

    private WebTestClient.ResponseSpec search(String query) {
        return client.get()
                .uri("/api/v1/organizations/{organizationId}/teams/{teamId}/configuration-search?q={q}",
                        organizationId, teamId, query)
                .accept(MediaType.APPLICATION_JSON)
                .exchange();
    }
}
