package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamApplicationService;
import io.crewscope.application.team.TeamInitializationStatus;
import io.crewscope.application.team.TeamMemberView;
import io.crewscope.application.team.TeamView;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the public Team route, Receipt, read model, ETag and safe error contracts. */
class TeamControllerTest {

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
          UtcTimestamp.parse("2026-08-08T03:00:00Z"));

  private TeamApplicationService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(TeamApplicationService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, true));
    client =
        WebTestClient.bindToController(new TeamController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void createsTeamUsingTheSharedAcceptedReceiptContract() {
    TeamInitialization initialization =
        TeamInitialization.create(
            actor, "Platform Crew", UtcTimestamp.parse("2026-08-08T03:00:00Z"));
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    when(service.createTeam(any(), any()))
        .thenReturn(CommandExecution.completed(initialization, receipt));

    client
        .post()
        .uri("/api/v1/organizations/{organizationId}/teams", organizationId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "create-team-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Platform Crew\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(receipt.commandId().toString())
        .jsonPath("$.committedVersion")
        .isEqualTo(0);
  }

  @Test
  void addsMemberAndMarksAnIdempotentReplay() {
    TeamInitialization initialization =
        TeamInitialization.create(
            actor, "Platform Crew", UtcTimestamp.parse("2026-08-08T03:00:00Z"));
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    when(service.addMember(any(), any(), any())).thenReturn(CommandExecution.replayed(receipt));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members",
            organizationId,
            initialization.team().id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "add-member-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"userPrincipalId\":\"" + actor.id() + "\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(receipt.commandId().toString());
  }

  @Test
  void listsMembersAndReturnsTheDefaultWorkspace() {
    TeamInitialization initialization =
        TeamInitialization.create(
            actor, "Platform Crew", UtcTimestamp.parse("2026-08-08T03:00:00Z"));
    when(service.listMembers(any(), any(), any()))
        .thenReturn(List.of(new TeamMemberView(initialization.ownerMember(), actor.displayName())));
    when(service.getDefaultWorkspace(any(), any(), any()))
        .thenReturn(initialization.defaultWorkspace());

    client
        .get()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members",
            organizationId,
            initialization.team().id())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$[0].userPrincipalId")
        .isEqualTo(actor.id().toString())
        .jsonPath("$[0].displayName")
        .isEqualTo("Owner");

    client
        .get()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/workspaces/default",
            organizationId,
            initialization.team().id())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("ETag", "\"0\"")
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo(initialization.defaultWorkspace().id().toString())
        .jsonPath("$.teamId")
        .isEqualTo(initialization.team().id().toString());
  }

  @Test
  void completesLegacyInitializationUsingTheAcceptedReceiptContract() {
    TeamInitialization initialization =
        TeamInitialization.create(actor, "Legacy Crew", UtcTimestamp.parse("2026-08-08T03:00:00Z"));
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    when(service.completeInitialization(any(), any(), any()))
        .thenReturn(CommandExecution.completed(initialization, receipt));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/initialization",
            organizationId,
            initialization.team().id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "complete-team-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"ownerPrincipalId\":\"" + actor.id() + "\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.domainEventId")
        .isEqualTo(receipt.domainEventId().toString())
        .jsonPath("$.committedVersion")
        .isEqualTo(1);
  }

  @Test
  void returnsInitializationRequiredWithoutInventingOwnerReferences() {
    TeamId teamId = TeamId.generate();
    TeamView pending =
        new TeamView(
            teamId,
            organizationId,
            "Legacy",
            TeamStatus.ACTIVE,
            TeamInitializationStatus.INITIALIZATION_REQUIRED,
            Optional.empty(),
            Optional.empty(),
            3);
    when(service.getTeam(any(), any(), any())).thenReturn(pending);

    client
        .get()
        .uri("/api/v1/organizations/{organizationId}/teams/{teamId}", organizationId, teamId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("ETag", "\"3\"")
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.initializationStatus")
        .isEqualTo("INITIALIZATION_REQUIRED")
        .jsonPath("$.ownerMemberId")
        .doesNotExist()
        .jsonPath("$.defaultWorkspaceId")
        .doesNotExist();
  }

  @Test
  void listsOnlyTheServiceAuthorizedTeamProjection() {
    TeamInitialization initialization =
        TeamInitialization.create(actor, "Platform", UtcTimestamp.parse("2026-08-08T03:00:00Z"));
    when(service.listTeams(any(), any())).thenReturn(List.of(TeamView.from(initialization.team())));

    client
        .get()
        .uri("/api/v1/organizations/{organizationId}/teams", organizationId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].name")
        .isEqualTo("Platform")
        .jsonPath("$[0].initializationStatus")
        .isEqualTo("READY");
  }

  @Test
  void mapsInvalidIdentifiersAndPolicyDenialToSafeErrors() {
    client
        .get()
        .uri("/api/v1/organizations/not-a-uuid/teams")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request")
        .jsonPath("$.details.field")
        .isEqualTo("organizationId");

    when(service.listTeams(any(), any())).thenThrow(new PolicyDeniedException("read Team list"));
    client
        .get()
        .uri("/api/v1/organizations/{organizationId}/teams", organizationId)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("policy_denied");
  }
}
