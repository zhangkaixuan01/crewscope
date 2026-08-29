package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.identity.AccountOrganizationResolution;
import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.team.InvitationMembershipDisposition;
import io.crewscope.application.team.InvitationToken;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamInvitationAcceptanceResult;
import io.crewscope.application.team.TeamInvitationApplicationException;
import io.crewscope.application.team.TeamInvitationApplicationFailure;
import io.crewscope.application.team.TeamInvitationApplicationService;
import io.crewscope.application.team.TeamInvitationIssueResult;
import io.crewscope.application.team.TeamInvitationPage;
import io.crewscope.application.team.TeamInvitationPreview;
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

/** HTTP projection, authentication and secret-minimization contract for M7-A05. */
class TeamInvitationControllerM7A05Test {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-29T02:00:00Z"));

    private TeamInvitationApplicationService invitations;
    private AuthenticatedAccountOrganizationResolver accountResolver;
    private OrganizationId organizationId;
    private Team team;
    private Principal inviter;
    private UserAccount account;
    private Principal accountPrincipal;
    private AccountOrganizationBinding binding;
    private InvitationToken token;
    private TeamInvitation invitation;
    private WebTestClient anonymous;
    private WebTestClient authenticated;

    @BeforeEach
    void setUp() {
        invitations = mock(TeamInvitationApplicationService.class);
        TeamRequestIdentityResolver teamIdentities = mock(TeamRequestIdentityResolver.class);
        accountResolver = mock(AuthenticatedAccountOrganizationResolver.class);
        organizationId = OrganizationId.generate();
        inviter = user("Inviter");
        accountPrincipal = user("Account User");
        team = Team.create(
                TeamId.generate(),
                organizationId,
                "Platform Team",
                TeamMemberId.generate(),
                WorkspaceId.generate(),
                inviter.id(),
                NOW);
        account = UserAccount.register(
                UserAccountId.generate(),
                "invitee-" + UUID.randomUUID().toString().substring(0, 8),
                "invitee@example.com",
                "Invitee",
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
        token = token(3);
        invitation = TeamInvitation.issue(
                TeamInvitationId.generate(),
                team,
                inviter,
                Optional.of(account.normalizedEmail()),
                BuiltInTeamRole.MEMBER,
                digest(3),
                UtcTimestamp.from(NOW.value().plusSeconds(3600)),
                NOW);
        when(teamIdentities.resolve(any(), eq(organizationId), any()))
                .thenReturn(Mono.just(new TeamAccessContext(inviter, false)));
        when(accountResolver.resolveSession(any(), any(), eq(organizationId)))
                .thenReturn(Optional.of(resolution));
        RegistrationProperties registration = new RegistrationProperties();
        registration.setOrganizationId(organizationId.toString());
        TeamInvitationController controller = new TeamInvitationController(
                invitations, teamIdentities, accountResolver, registration);
        anonymous = WebTestClient.bindToController(controller)
                .controllerAdvice(new ApiExceptionHandler())
                .build();
        authenticated = authenticatedClient(
                controller, account.id().value(), account.securityVersion().value());
    }

    @Test
    void creationReturnsTheSecretOnceAndReplayReturnsOnlyTheReceipt() {
        CommandReceipt receipt = receipt(invitation.version());
        when(invitations.create(any(), eq(team.id()), any()))
                .thenAnswer(call -> {
                    assertTrue(Thread.currentThread().getName().contains("boundedElastic"));
                    return CommandExecution.completed(
                            new TeamInvitationIssueResult(invitation, token), receipt);
                })
                .thenReturn(CommandExecution.replayed(receipt));

        WebTestClient.RequestHeadersSpec<?> request = anonymous.post()
                .uri(managementPath())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "invite-http-create-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"targetEmail":"Invitee@Example.com","targetRole":"MEMBER",
                         "expiresInMinutes":60}
                        """);
        request.exchange()
                .expectStatus().isAccepted()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody()
                .jsonPath("$.token").isEqualTo(token.reveal())
                .jsonPath("$.invitation.id").isEqualTo(invitation.id().toString())
                .jsonPath("$.invitation.tokenDigest").doesNotExist();

        anonymous.post()
                .uri(managementPath())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "invite-http-create-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"targetEmail":"Invitee@Example.com","targetRole":"MEMBER",
                         "expiresInMinutes":60}
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
                .expectBody()
                .jsonPath("$.token").doesNotExist()
                .jsonPath("$.invitation").doesNotExist()
                .jsonPath("$.command.commandId").isEqualTo(receipt.commandId().toString());
    }

    @Test
    void listAndPreviewNeverProjectDigestEmailOrInviterIntoThePublicPreview() {
        when(invitations.list(
                        any(), eq(organizationId), eq(team.id()), eq(Optional.empty()), eq(50)))
                .thenReturn(new TeamInvitationPage(List.of(invitation), Optional.empty()));
        when(invitations.preview(any())).thenReturn(TeamInvitationPreview.available(
                invitation.id(),
                team.name(),
                invitation.targetRole(),
                invitation.expiresAt(),
                true));

        anonymous.get()
                .uri(managementPath())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].targetEmail").isEqualTo("invitee@example.com")
                .jsonPath("$.items[0].tokenDigest").doesNotExist();

        anonymous.post()
                .uri("/api/v1/invitations/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + token.reveal() + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody()
                .jsonPath("$.state").isEqualTo("AVAILABLE")
                .jsonPath("$.teamName").isEqualTo(team.name())
                .jsonPath("$.targetEmail").doesNotExist()
                .jsonPath("$.invitedByPrincipalId").doesNotExist()
                .jsonPath("$.tokenDigest").doesNotExist();
    }

    @Test
    void acceptanceRequiresAValidSessionAndMapsMalformedTokensWithoutCallingTheService() {
        anonymous.post()
                .uri("/api/v1/invitations/accept")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "invite-http-accept-anonymous")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + token.reveal() + "\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("authentication_required");

        anonymous.post()
                .uri("/api/v1/invitations/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + "a".repeat(43) + "\"}")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invitation_invalid")
                .jsonPath("$.details").isEmpty();

        verify(invitations, never()).accept(any(), any());
    }

    @Test
    void validSessionAcceptsAndInvalidCursorOrOwnerRoleFailsClosed() {
        TeamMember membership = team.acceptInvitedMember(
                TeamMemberId.generate(), accountPrincipal, inviter.id(), NOW);
        TeamInvitationAcceptanceResult result = new TeamInvitationAcceptanceResult(
                invitation.accept(
                        account,
                        binding,
                        accountPrincipal,
                        team,
                        membership,
                        digest(3),
                        NOW),
                membership,
                InvitationMembershipDisposition.CREATED,
                true);
        CommandReceipt receipt = receipt(result.invitation().version());
        when(invitations.accept(any(), any()))
                .thenReturn(CommandExecution.completed(result, receipt));

        authenticated.post()
                .uri("/api/v1/invitations/accept")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "invite-http-accept-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + token.reveal() + "\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.commandId").isEqualTo(receipt.commandId().toString());

        anonymous.get()
                .uri(managementPath() + "?after=not-a-cursor")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_cursor");

        anonymous.post()
                .uri(managementPath())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "invite-owner-role")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"targetRole":"TEAM_OWNER","expiresInMinutes":60}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.details.field").isEqualTo("targetRole");
    }

    @Test
    void applicationAcceptanceFailureUsesOneNonIdentifyingEnvelope() {
        when(invitations.accept(any(), any())).thenThrow(
                new TeamInvitationApplicationException(
                        TeamInvitationApplicationFailure.INVALID_INVITATION));

        authenticated.post()
                .uri("/api/v1/invitations/accept")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "invite-http-invalid-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + token.reveal() + "\"}")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("invitation_invalid")
                .jsonPath("$.message").isEqualTo("Invitation could not be processed")
                .jsonPath("$.details").isEmpty();
    }

    private String managementPath() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + team.id() + "/invitations";
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

    private static CommandReceipt receipt(long version) {
        return new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), version, UUID.randomUUID());
    }

    private static WebTestClient authenticatedClient(
            TeamInvitationController controller, UUID accountId, long securityVersion) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new BrowserSessionPrincipal(accountId, securityVersion),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        WebFilter principal = (exchange, chain) ->
                chain.filter(exchange.mutate().principal(Mono.just(authentication)).build());
        return WebTestClient.bindToController(controller)
                .controllerAdvice(new ApiExceptionHandler())
                .webFilter(principal)
                .build();
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
