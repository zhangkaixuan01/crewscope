package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.principal.PrincipalDirectoryEntry;
import io.crewscope.application.principal.PrincipalDirectoryPage;
import io.crewscope.application.principal.PrincipalDirectoryQueryService;
import io.crewscope.application.principal.PrincipalKind;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Verifies the read-only subject directory response and its privacy boundary. */
class PrincipalDirectoryControllerTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private PrincipalDirectoryQueryService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(PrincipalDirectoryQueryService.class);
    Principal actor = Principal.create(
        PrincipalId.generate(),
        PrincipalScope.organization(organizationId),
        PrincipalType.USER,
        Optional.empty(),
        "Owner",
        Optional.empty(),
        PrincipalVisibility.ORGANIZATION,
        UtcTimestamp.parse("2026-08-08T03:00:00Z"));
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, true));
    client = WebTestClient.bindToController(new PrincipalDirectoryController(service, resolver))
        .controllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void returnsUserAndAgentRowsWithRolesAndOffset() {
    PrincipalId userId = PrincipalId.generate();
    PrincipalId agentId = PrincipalId.generate();
    PrincipalDirectoryPage page = new PrincipalDirectoryPage(
        List.of(
            new PrincipalDirectoryEntry(
                userId, PrincipalKind.USER, "Alice", PrincipalStatus.ACTIVE, List.of("TEAM_OWNER")),
            new PrincipalDirectoryEntry(
                agentId, PrincipalKind.AGENT, "Build Agent", PrincipalStatus.ACTIVE, List.of())),
        OptionalInt.of(50));
    when(service.search(any(), any())).thenReturn(page);

    client.get()
        .uri(uriBuilder -> uriBuilder
            .path("/api/v1/organizations/{organizationId}/teams/{teamId}/principals")
            .queryParam("q", "Al")
            .queryParam("offset", 0)
            .queryParam("limit", 50)
            .build(organizationId, teamId))
        .exchange()
        .expectStatus().isOk()
        .expectHeader().valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.items[0].principalId").isEqualTo(userId.toString())
        .jsonPath("$.items[0].kind").isEqualTo("USER")
        .jsonPath("$.items[0].displayName").isEqualTo("Alice")
        .jsonPath("$.items[0].roles[0]").isEqualTo("TEAM_OWNER")
        .jsonPath("$.items[1].kind").isEqualTo("AGENT")
        .jsonPath("$.nextOffset").isEqualTo(50)
        .jsonPath("$.items[0].email").doesNotExist()
        .jsonPath("$.items[0].token").doesNotExist();
  }

  @Test
  void rejectsDirectoryPagesAboveTheContractLimit() {
    client.get()
        .uri(uriBuilder -> uriBuilder
            .path("/api/v1/organizations/{organizationId}/teams/{teamId}/principals")
            .queryParam("limit", 201)
            .build(organizationId, teamId))
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_request");
  }
}
