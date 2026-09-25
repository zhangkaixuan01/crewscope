package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMemberLifecycleApplicationService;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.LastOwnerProtectionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the ADR-038 lifecycle routes, If-Match, idempotency and safe error contracts. */
class TeamMemberLifecycleControllerTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-09T06:00:00Z");

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
  private final TeamId teamId =
      TeamInitialization.create(actor, "Platform Crew", NOW).team().id();
  private final TeamMemberId memberId = TeamMemberId.generate();

  private TeamMemberLifecycleApplicationService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(TeamMemberLifecycleApplicationService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, true));
    client =
        WebTestClient.bindToController(new TeamMemberLifecycleController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void suspendsMemberThroughTheAcceptedReceiptContract() {
    TeamMember member = member();
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    when(service.suspendMember(any(), any(), any(), eq(3L)))
        .thenReturn(CommandExecution.completed(member, receipt));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/{memberId}/suspend",
            organizationId,
            teamId,
            memberId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "suspend-http-1")
        .header(ApiHeaders.IF_MATCH, "\"3\"")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(receipt.commandId().toString())
        .jsonPath("$.committedVersion")
        .isEqualTo(1);

    verify(service).suspendMember(any(), eq(teamId), eq(memberId), eq(3L));
  }

  @Test
  void marksAnIdempotentReplayOfTheSameCommand() {
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    when(service.activateMember(any(), any(), any(), eq(2L)))
        .thenReturn(CommandExecution.replayed(receipt));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/{memberId}/activate",
            organizationId,
            teamId,
            memberId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "activate-http-1")
        .header(ApiHeaders.IF_MATCH, "\"2\"")
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
  void requiresIdempotencyKeyQuotedIfMatchAndValidIdentifiers() {
    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/{memberId}/remove",
            organizationId,
            teamId,
            memberId)
        .header(ApiHeaders.IF_MATCH, "\"4\"")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/{memberId}/remove",
            organizationId,
            teamId,
            memberId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "remove-http-1")
        .exchange()
        .expectStatus()
        .isEqualTo(428)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("precondition_required");

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/not-a-uuid/remove",
            organizationId,
            teamId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "remove-http-2")
        .header(ApiHeaders.IF_MATCH, "\"4\"")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request")
        .jsonPath("$.details.field")
        .isEqualTo("memberId");
  }

  @Test
  void leavesUsingTheCallerMembershipRoute() {
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    when(service.leaveTeam(any(), any(), eq(0L)))
        .thenReturn(CommandExecution.completed(member(), receipt));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/me/leave",
            organizationId,
            teamId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "leave-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(receipt.commandId().toString());

    verify(service).leaveTeam(any(), eq(teamId), eq(0L));
  }

  @Test
  void grantsAndRevokesRolesThroughTheGrantCoordinate() {
    CommandReceipt grantReceipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    when(service.grantRole(any(), any(), any(), eq(0L), eq("TEAM_LEAD")))
        .thenReturn(CommandExecution.completed(member(), grantReceipt));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/{memberId}/roles",
            organizationId,
            teamId,
            memberId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "grant-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"roleKey\":\"TEAM_LEAD\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(grantReceipt.commandId().toString());

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/{memberId}/roles",
            organizationId,
            teamId,
            memberId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "grant-http-2")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"roleKey\":\"\"}")
        .exchange()
        .expectStatus()
        .isBadRequest();

    UUID grantId = UUID.randomUUID();
    CommandReceipt revokeReceipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 2, UUID.randomUUID());
    when(service.revokeRole(any(), any(), any(), eq(1L), any()))
        .thenReturn(CommandExecution.completed(member(), revokeReceipt));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/{memberId}"
                + "/roles/{grantId}/revoke",
            organizationId,
            teamId,
            memberId,
            grantId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "revoke-http-1")
        .header(ApiHeaders.IF_MATCH, "\"1\"")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(revokeReceipt.commandId().toString());

    verify(service)
        .revokeRole(
            any(), eq(teamId), eq(memberId), eq(1L), eq(new io.crewscope.domain.team.MemberRoleId(grantId)));
  }

  @Test
  void transfersOwnershipUsingTheTargetMemberBody() {
    TeamInitialization initialization = TeamInitialization.create(actor, "Platform Crew", NOW);
    TeamMember previousOwner = initialization.ownerMember();
    TeamMember newOwner =
        TeamMember.join(TeamMemberId.generate(), scope(), actor, TeamJoinMethod.OIDC, NOW);
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    when(service.transferOwnership(any(), any(), any(), eq(0L)))
        .thenReturn(
            CommandExecution.completed(
                new TeamMemberLifecycleApplicationService.OwnershipTransferResult(
                    initialization.team(), previousOwner, newOwner),
                receipt));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/transfer-ownership",
            organizationId,
            teamId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "transfer-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"targetMemberId\":\"" + memberId + "\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(receipt.commandId().toString());

    verify(service).transferOwnership(any(), eq(teamId), eq(memberId), eq(0L));
  }

  @Test
  void mapsLastOwnerProtectionAndStaleIfMatchToConflictErrors() {
    when(service.suspendMember(any(), any(), any(), eq(3L)))
        .thenThrow(new LastOwnerProtectionException(teamId.toString(), memberId));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/{memberId}/suspend",
            organizationId,
            teamId,
            memberId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "suspend-http-conflict")
        .header(ApiHeaders.IF_MATCH, "\"3\"")
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("last_owner_protection");

    when(service.activateMember(any(), any(), any(), eq(9L)))
        .thenThrow(new OptimisticLockConflictException("TEAM_MEMBER", memberId, 9L, 8L));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/{memberId}/activate",
            organizationId,
            teamId,
            memberId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "activate-http-conflict")
        .header(ApiHeaders.IF_MATCH, "\"9\"")
        .exchange()
        .expectStatus()
        .isEqualTo(409);
  }

  @Test
  void resolverCarriesTheResolvedIdentityIntoTheCommandContext() {
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    when(service.removeMember(any(), any(), any(), eq(0L)))
        .thenReturn(CommandExecution.completed(member(), receipt));

    client
        .post()
        .uri(
            "/api/v1/organizations/{organizationId}/teams/{teamId}/members/{memberId}/remove",
            organizationId,
            teamId,
            memberId)
        .header(ApiHeaders.IDEMPOTENCY_KEY, "remove-http-3")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isAccepted();

    org.mockito.ArgumentCaptor<io.crewscope.application.team.TeamCommandContext> context =
        org.mockito.ArgumentCaptor.forClass(
            io.crewscope.application.team.TeamCommandContext.class);
    verify(service).removeMember(context.capture(), eq(teamId), eq(memberId), eq(0L));
    assertEquals(actor.id(), context.getValue().access().actor().id());
    assertEquals("remove-http-3", context.getValue().idempotencyKey().value());
  }

  private TeamMember member() {
    return TeamMember.join(memberId, scope(), actor, TeamJoinMethod.OIDC, NOW);
  }

  private TeamScope scope() {
    return new TeamScope(organizationId, teamId);
  }
}
