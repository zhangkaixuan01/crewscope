package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandResult;
import io.crewscope.application.command.CommandResultQueryService;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.identity.AccountOrganizationResolution;
import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.team.InvitationMembershipDisposition;
import io.crewscope.application.team.InvitationToken;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamInvitationAcceptanceResult;
import io.crewscope.application.team.TeamInvitationApplicationService;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.InvitationTokenDigest;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * A07 R29: an acceptance answer names its committed Team and member; the same key replayed later
 * backfills the identical stored coordinate instead of letting the client guess.
 */
class InvitationAcceptanceCoordinateTest {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-09-25T08:00:00Z"));

    private TeamInvitationApplicationService invitations;
    private CommandResultQueryService commandResults;
    private OrganizationId organizationId;
    private Team team;
    private Principal accountPrincipal;
    private UserAccount account;
    private AccountOrganizationBinding binding;
    private InvitationToken token;
    private TeamInvitation invitation;
    private TeamMember membership;
    private WebTestClient authenticated;

    @BeforeEach
    void setUp() {
        invitations = mock(TeamInvitationApplicationService.class);
        commandResults = mock(CommandResultQueryService.class);
        AuthenticatedAccountOrganizationResolver accountResolver =
                mock(AuthenticatedAccountOrganizationResolver.class);
        organizationId = OrganizationId.generate();
        Principal inviter = user("Inviter");
        accountPrincipal = user("Account User");
        team = Team.create(
                TeamId.generate(),
                organizationId,
                "Coordinate Team",
                TeamMemberId.generate(),
                WorkspaceId.generate(),
                inviter.id(),
                NOW);
        account = UserAccount.register(
                UserAccountId.generate(),
                "coordinate-" + UUID.randomUUID().toString().substring(0, 8),
                "coordinate@example.com",
                "Coordinate User",
                NOW);
        binding = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                organizationId,
                accountPrincipal,
                NOW);
        LoginIdentity identity =
                LoginIdentity.local(LoginIdentityId.generate(), account.id(), NOW);
        AccountOrganizationResolution resolution =
                new AccountOrganizationResolution(account, identity, binding, accountPrincipal);
        token = token(5);
        invitation = TeamInvitation.issue(
                TeamInvitationId.generate(),
                team,
                inviter,
                Optional.of(account.normalizedEmail()),
                BuiltInTeamRole.MEMBER,
                digest(5),
                UtcTimestamp.from(NOW.value().plusSeconds(3600)),
                NOW);
        membership = team.acceptInvitedMember(
                TeamMemberId.generate(), accountPrincipal, inviter.id(), NOW);
        when(accountResolver.resolveSession(any(), any(), eq(organizationId)))
                .thenReturn(Optional.of(resolution));
        RegistrationProperties registration = new RegistrationProperties();
        registration.setOrganizationId(organizationId.toString());
        TeamInvitationController controller = new TeamInvitationController(
                invitations,
                mock(TeamRequestIdentityResolver.class),
                accountResolver,
                commandResults,
                registration);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new BrowserSessionPrincipal(account.id().value(), account.securityVersion().value()),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        WebFilter principal = (exchange, chain) ->
                chain.filter(exchange.mutate().principal(Mono.just(authentication)).build());
        authenticated = WebTestClient.bindToController(controller)
                .controllerAdvice(new ApiExceptionHandler())
                .webFilter(principal)
                .build();
    }

    @Test
    void firstAcceptanceReturnsTheCommittedCoordinates() {
        TeamInvitationAcceptanceResult result = acceptance();
        CommandReceipt receipt = receipt();
        when(invitations.accept(any(), any()))
                .thenReturn(CommandExecution.completed(result, receipt));

        accept("coordinate-accept-1")
                .expectStatus().isAccepted()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody()
                .jsonPath("$.command.commandId").isEqualTo(receipt.commandId().toString())
                .jsonPath("$.acceptance.teamId").isEqualTo(team.id().toString())
                .jsonPath("$.acceptance.memberId").isEqualTo(membership.id().toString())
                .jsonPath("$.acceptance.invitationId").isEqualTo(invitation.id().toString())
                .jsonPath("$.acceptance.membershipDisposition").isEqualTo("CREATED")
                .jsonPath("$.acceptance.roleGrantCreated").isEqualTo(true);
    }

    @Test
    void sameKeyReplayBackfillsTheIdenticalStoredMemberCoordinate() {
        CommandReceipt receipt = receipt();
        when(invitations.accept(any(), any()))
                .thenReturn(CommandExecution.replayed(receipt));
        when(commandResults.find(any(), eq(organizationId), any(IdempotencyKey.class)))
                .thenReturn(Optional.of(storedResult(receipt)));

        accept("coordinate-accept-1")
                .expectStatus().isAccepted()
                .expectHeader().valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
                .expectBody()
                .jsonPath("$.command.commandId").isEqualTo(receipt.commandId().toString())
                .jsonPath("$.acceptance.teamId").isEqualTo(team.id().toString())
                .jsonPath("$.acceptance.memberId").isEqualTo(membership.id().toString())
                .jsonPath("$.acceptance.invitationId").doesNotExist()
                .jsonPath("$.acceptance.membershipDisposition").doesNotExist()
                .jsonPath("$.acceptance.roleGrantCreated").doesNotExist();
    }

    @Test
    void replayWithoutStoredResultOmitsTheAcceptanceBlockWithoutFailing() {
        when(invitations.accept(any(), any()))
                .thenReturn(CommandExecution.replayed(receipt()));
        when(commandResults.find(any(), eq(organizationId), any(IdempotencyKey.class)))
                .thenReturn(Optional.empty());

        accept("coordinate-accept-lost")
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.command.commandId").exists()
                .jsonPath("$.acceptance.teamId").doesNotExist();
    }

    private WebTestClient.ResponseSpec accept(String idempotencyKey) {
        return authenticated.post()
                .uri("/api/v1/invitations/accept")
                .header(ApiHeaders.IDEMPOTENCY_KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + token.reveal() + "\"}")
                .exchange();
    }

    private TeamInvitationAcceptanceResult acceptance() {
        return new TeamInvitationAcceptanceResult(
                invitation.accept(account, binding, accountPrincipal, team, membership,
                        digest(5), NOW),
                membership,
                InvitationMembershipDisposition.CREATED,
                true);
    }

    private CommandResult storedResult(CommandReceipt receipt) {
        return new CommandResult(
                organizationId,
                new IdempotencyKey("coordinate-accept-1"),
                accountPrincipal.id(),
                "ACCEPT_TEAM_INVITATION",
                team.id(),
                Optional.empty(),
                CommandResult.ResourceType.TEAM_MEMBER,
                membership.id().value(),
                membership.version(),
                receipt,
                NOW);
    }

    private Principal user(String name) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                name,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }

    private static CommandReceipt receipt() {
        return new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    }

    private static InvitationToken token(int seed) {
        byte[] bytes = new byte[InvitationToken.ENTROPY_BYTES];
        bytes[0] = (byte) seed;
        return new InvitationToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    private static InvitationTokenDigest digest(int seed) {
        byte[] bytes = new byte[InvitationTokenDigest.BYTE_LENGTH];
        bytes[0] = (byte) seed;
        return InvitationTokenDigest.fromBytes(bytes);
    }
}
