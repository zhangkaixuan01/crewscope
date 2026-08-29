package io.crewscope.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.team.InvitationToken;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.DefaultPersonalAgentService;
import io.crewscope.application.team.TeamInvitationAcceptanceService;
import io.crewscope.application.team.TeamInvitationRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.RegistrationMode;
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
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamRoleStatus;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Registration transaction hardening checks that are not visible at the HTTP mock boundary. */
class LocalAccountRegistrationServiceM7A01Test {

    private static final UtcTimestamp NOW = UtcTimestamp.from(Instant.parse("2026-08-29T01:00:00Z"));
    private static final UtcTimestamp LATER = UtcTimestamp.from(Instant.parse("2026-08-29T02:00:00Z"));

    @Test
    void invitationRegistrationRejectsADisabledBuiltInRoleBeforeMembershipMutation() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal inviter = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Inviter",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        Team team = Team.create(
                TeamId.generate(),
                organizationId,
                "Platform Team",
                TeamMemberId.generate(),
                WorkspaceId.generate(),
                inviter.id(),
                NOW);
        InvitationToken token = token(7);
        InvitationTokenDigest digest = digest(7);
        TeamInvitation invitation = TeamInvitation.issue(
                TeamInvitationId.generate(),
                team,
                inviter,
                Optional.empty(),
                BuiltInTeamRole.MEMBER,
                digest,
                UtcTimestamp.from(Instant.parse("2026-09-05T01:00:00Z")),
                NOW);
        TeamRole disabledMemberRole = TeamRole.createBuiltIn(
                        TeamRoleId.generate(), team.scope(), BuiltInTeamRole.MEMBER, NOW)
                .transitionTo(TeamRoleStatus.DISABLED, LATER);

        UserAccountRepository accounts = mock(UserAccountRepository.class);
        LoginIdentityRepository identities = mock(LoginIdentityRepository.class);
        LocalCredentialStore credentials = mock(LocalCredentialStore.class);
        PrincipalRepository principals = mock(PrincipalRepository.class);
        AccountOrganizationBindingRepository bindings =
                mock(AccountOrganizationBindingRepository.class);
        TeamInvitationRepository invitations = mock(TeamInvitationRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        TeamMemberRepository members = mock(TeamMemberRepository.class);
        TeamRoleRepository roles = mock(TeamRoleRepository.class);
        MemberRoleRepository memberRoles = mock(MemberRoleRepository.class);
        LocalPasswordAuthentication passwords = mock(LocalPasswordAuthentication.class);
        DomainEventStore events = mock(DomainEventStore.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        CommandReceiptStore receipts = mock(CommandReceiptStore.class);

        when(accounts.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(identities.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(principals.createLocalUser(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(bindings.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(invitations.lockByTokenDigest(digest)).thenReturn(Optional.of(invitation));
        when(teams.lockById(organizationId, team.id())).thenReturn(Optional.of(team));
        when(roles.findByTeam(organizationId, team.id())).thenReturn(java.util.List.of(disabledMemberRole));
        when(receipts.findCompleted(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(passwords.encodeForStorage(any())).thenReturn(CompletableFuture.completedFuture(
                new LocalPasswordHash("{argon2id}review-fixture-password-hash")));
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };
        LocalAccountRegistrationService service = new LocalAccountRegistrationService(
                accounts,
                identities,
                credentials,
                principals,
                bindings,
                invitations,
                teams,
                members,
                roles,
                memberRoles,
                mock(WorkspaceRepository.class),
                mock(DefaultPersonalAgentService.class),
                new TeamInvitationAcceptanceService(),
                Optional.of(ignored -> digest),
                passwords,
                events,
                outbox,
                receipts,
                transactions,
                () -> NOW,
                Runnable::run);
        LocalAccountRegistrationContext context = new LocalAccountRegistrationContext(
                organizationId,
                RegistrationMode.INVITE_ONLY,
                IdempotencyKey.from("registration-disabled-role"),
                UUID.randomUUID(),
                Optional.empty());

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> service.register(
                                context,
                                new LocalAccountRegistrationCommand(
                                        "alice",
                                        "alice@example.com",
                                        "Alice",
                                        "Correct-Horse-Battery-Staple",
                                        Optional.of(token)))
                        .toCompletableFuture()
                        .join());

        LocalAccountRegistrationException registrationFailure = assertInstanceOf(
                LocalAccountRegistrationException.class, failure.getCause());
        assertEquals(
                LocalAccountRegistrationFailure.INVITATION_INVALID,
                registrationFailure.failure());
        verify(members, never()).create(any());
        verify(memberRoles, never()).create(any());
        verify(invitations, never()).update(any(), anyLong());
    }

    private static InvitationToken token(int fill) {
        byte[] bytes = new byte[InvitationToken.ENTROPY_BYTES];
        java.util.Arrays.fill(bytes, (byte) fill);
        return new InvitationToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    private static InvitationTokenDigest digest(int fill) {
        byte[] bytes = new byte[InvitationTokenDigest.BYTE_LENGTH];
        java.util.Arrays.fill(bytes, (byte) fill);
        return InvitationTokenDigest.fromBytes(bytes);
    }
}
