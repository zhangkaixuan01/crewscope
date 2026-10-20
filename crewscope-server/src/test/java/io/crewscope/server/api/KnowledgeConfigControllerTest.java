package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.retrieval.KnowledgeSwitchBoard;
import io.crewscope.application.retrieval.KnowledgeSwitchQueryService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Proves the M13b-A03 knowledge-switch HTTP contract: booleans only (no environment-variable
 * names), member-level denial maps to 403, unknown Team to 404, malformed ids to 400, and the
 * answer is no-store so a flipped deployment switch is never served from a cache.
 */
class KnowledgeConfigControllerTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-20T09:00:00Z");

  private final OrganizationId organizationId = OrganizationId.generate();
  private final Principal actor =
      Principal.create(
          PrincipalId.generate(),
          PrincipalScope.organization(organizationId),
          PrincipalType.USER,
          Optional.empty(),
          "Owner",
          Optional.empty(),
          PrincipalVisibility.ORGANIZATION,
          NOW);
  private final TeamInitialization initialization = TeamInitialization.create(actor, "Platform", NOW);
  private final TeamId teamId = initialization.team().id();

  private KnowledgeSwitchQueryService service;
  private WebTestClient client;

  private String root() {
    return "/api/v1/organizations/" + organizationId + "/teams/" + teamId + "/knowledge";
  }

  @BeforeEach
  void setUp() {
    service = mock(KnowledgeSwitchQueryService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(new KnowledgeConfigController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void configAnswersTheSixBooleansNoStore() {
    KnowledgeSwitchBoard board =
        new KnowledgeSwitchBoard(true, true, false, true, false, false);
    when(service.switches(any(), any(), any())).thenReturn(board);

    client.get().uri(root() + "/config").exchange()
        .expectStatus().isOk()
        .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        .expectBody()
        .jsonPath("$.vectorEnabled").isEqualTo(true)
        .jsonPath("$.indexEnabled").isEqualTo(true)
        .jsonPath("$.indexWorkerEnabled").isEqualTo(false)
        .jsonPath("$.retrievalEnabled").isEqualTo(true)
        .jsonPath("$.injectionEnabled").isEqualTo(false)
        .jsonPath("$.skillEnabled").isEqualTo(false);
  }

  @Test
  void configAnswersTheAllOffDefaultBoard() {
    KnowledgeSwitchBoard closed = new KnowledgeSwitchBoard(false, false, false, false, false, false);
    when(service.switches(any(), any(), any())).thenReturn(closed);

    client.get().uri(root() + "/config").exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.vectorEnabled").isEqualTo(false)
        .jsonPath("$.indexEnabled").isEqualTo(false)
        .jsonPath("$.indexWorkerEnabled").isEqualTo(false)
        .jsonPath("$.retrievalEnabled").isEqualTo(false)
        .jsonPath("$.injectionEnabled").isEqualTo(false)
        .jsonPath("$.skillEnabled").isEqualTo(false);
  }

  @Test
  void membershipDenialMapsToForbidden() {
    when(service.switches(any(), any(), any()))
        .thenThrow(new PolicyDeniedException("access this Team's knowledge"));

    client.get().uri(root() + "/config").exchange()
        .expectStatus().isForbidden();
  }

  @Test
  void unknownTeamMapsToNotFound() {
    when(service.switches(any(), any(), any()))
        .thenThrow(new AggregateNotFoundException("Team", teamId));

    client.get().uri(root() + "/config").exchange()
        .expectStatus().isNotFound();
  }

  @Test
  void malformedPathIdsAreRejected() {
    client.get()
        .uri("/api/v1/organizations/not-a-uuid/teams/" + teamId + "/knowledge/config")
        .exchange()
        .expectStatus().isBadRequest();

    client.get()
        .uri("/api/v1/organizations/" + organizationId + "/teams/not-a-uuid/knowledge/config")
        .exchange()
        .expectStatus().isBadRequest();
  }
}
