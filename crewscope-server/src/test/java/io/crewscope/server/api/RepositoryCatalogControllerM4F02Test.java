package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.RepositoryCatalogApplicationService;
import io.crewscope.application.coding.RepositoryCatalogAvailability;
import io.crewscope.application.coding.RepositoryCatalogEntry;
import io.crewscope.application.coding.RepositoryCatalogUnavailableException;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class RepositoryCatalogControllerM4F02Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private RepositoryCatalogApplicationService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Owner",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                UtcTimestamp.parse("2026-08-20T01:00:00Z"));
        service = mock(RepositoryCatalogApplicationService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(new RepositoryCatalogController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsOnlyThePathFreeCatalogWhitelistWithNoStore() {
        when(service.list(any(), any(), any(), any())).thenReturn(List.of(
                new RepositoryCatalogEntry(
                        RepositoryKey.parse("crewscope-java"),
                        RepositoryCatalogAvailability.AVAILABLE,
                        Optional.of("main"))));

        client.get()
                .uri(base())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items[0].repositoryKey")
                .isEqualTo("crewscope-java")
                .jsonPath("$.items[0].availability")
                .isEqualTo("AVAILABLE")
                .jsonPath("$.items[0].suggestedDefaultBranch")
                .isEqualTo("main")
                .jsonPath("$.items[0].canonicalPath")
                .doesNotExist()
                .jsonPath("$.items[0].managedRoot")
                .doesNotExist();
    }

    @Test
    void mapsServerOnlyProfilesToTheStableRetryableCatalogError() {
        when(service.list(any(), any(), any(), any()))
                .thenThrow(new RepositoryCatalogUnavailableException());

        client.get()
                .uri(base())
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("repository_catalog_unavailable")
                .jsonPath("$.retryable")
                .isEqualTo(true)
                .jsonPath("$.details")
                .isEmpty();
    }

    private String base() {
        return "/api/v1/organizations/" + organizationId
                + "/teams/" + teamId
                + "/work-projects/" + projectId
                + "/repository-catalog";
    }
}
