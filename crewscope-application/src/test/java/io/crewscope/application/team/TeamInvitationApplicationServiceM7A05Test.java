package io.crewscope.application.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.InvitationTokenDigest;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationConflictException;
import io.crewscope.domain.team.TeamInvitationId;
import io.crewscope.domain.team.TeamInvitationStatus;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamRoleStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Application-level proof for M7-A05 invitation management and current-account acceptance. */
class TeamInvitationApplicationServiceM7A05Test {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-29T01:00:00Z"));
    private static final InvitationToken TOKEN = token(7);
    private static final InvitationTokenDigest DIGEST = digest(7);

    @Test
    void adminCreatesAndReplaysWithoutRecoveringThePlaintextToken() {
        Fixture fixture = new Fixture();
        TeamCommandContext context = fixture.command(fixture.admin, "invite-create-1");

        CommandExecution<TeamInvitationIssueResult> first = fixture.service.create(
                context,
                fixture.team.id(),
                new CreateTeamInvitationCommand(
                        Optional.of(fixture.invitee.normalizedEmail()),
                        BuiltInTeamRole.MEMBER,
                        Duration.ofDays(7)));
        CommandExecution<TeamInvitationIssueResult> replay = fixture.service.create(
                context,
                fixture.team.id(),
                new CreateTeamInvitationCommand(
                        Optional.of(fixture.invitee.normalizedEmail()),
                        BuiltInTeamRole.MEMBER,
                        Duration.ofDays(7)));

        assertEquals(TOKEN.reveal(), first.result().orElseThrow().revealToken());
        assertTrue(replay.replayed());
        assertTrue(replay.result().isEmpty());
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(1, fixture.repository.invitations.size());
        assertEquals(1, fixture.repository.events.size());
        assertEquals(1, fixture.repository.outbox.size());
        assertFalse(fixture.repository.invitations.values().toString().contains(TOKEN.reveal()));
    }

    @Test
    void sameIdempotencyKeyCannotCrossCausationChains() {
        Fixture fixture = new Fixture();
        UUID firstCause = UUID.randomUUID();
        UUID secondCause = UUID.randomUUID();
        CreateTeamInvitationCommand command = new CreateTeamInvitationCommand(
                Optional.empty(), BuiltInTeamRole.MEMBER, Duration.ofDays(7));

        fixture.service.create(
                fixture.command(fixture.admin, "invite-causation-1", Optional.of(firstCause)),
                fixture.team.id(),
                command);

        assertThrows(
                IdempotencyConflictException.class,
                () -> fixture.service.create(
                        fixture.command(
                                fixture.admin,
                                "invite-causation-1",
                                Optional.of(secondCause)),
                        fixture.team.id(),
                        command));
    }

    @Test
    void managementRequiresMemberManageAndRevocationHasOneStableTerminalFact() {
        Fixture fixture = new Fixture();
        TeamInvitation invitation = fixture.issue("invite-manage-create");

        TeamInvitationPage page = fixture.service.list(
                new TeamAccessContext(fixture.admin, false),
                fixture.organizationId,
                fixture.team.id(),
                Optional.empty(),
                20);
        CommandExecution<TeamInvitation> revoked = fixture.service.revoke(
                fixture.command(fixture.admin, "invite-revoke-1"),
                fixture.team.id(),
                invitation.id());

        assertEquals(List.of(invitation), page.invitations());
        assertEquals(
                TeamInvitationStatus.REVOKED,
                revoked.result().orElseThrow().status());
        assertEquals(2, fixture.repository.events.size());
        assertEquals(2, fixture.repository.outbox.size());
        assertThrows(
                TeamInvitationApplicationException.class,
                () -> fixture.service.revoke(
                        fixture.command(fixture.admin, "invite-revoke-2"),
                        fixture.team.id(),
                        invitation.id()));
        assertThrows(
                PolicyDeniedException.class,
                () -> fixture.service.list(
                        new TeamAccessContext(fixture.member, false),
                        fixture.organizationId,
                        fixture.team.id(),
                        Optional.empty(),
                        20));
        assertThrows(
                PolicyDeniedException.class,
                () -> fixture.service.revoke(
                        fixture.command(fixture.member, "member-probe-revoke"),
                        fixture.team.id(),
                        TeamInvitationId.generate()));
    }

    @Test
    void previewExposesOnlyAvailableMetadataAndCollapsesTerminalStates() {
        Fixture fixture = new Fixture();
        TeamInvitation invitation = fixture.issue("invite-preview-create");

        TeamInvitationPreview available = fixture.service.preview(TOKEN);
        fixture.time.now = invitation.expiresAt();
        TeamInvitationPreview expired = fixture.service.preview(TOKEN);
        TeamInvitationPreview unknown = fixture.service.preview(token(99));

        assertEquals(TeamInvitationPreviewState.AVAILABLE, available.state());
        assertEquals(Optional.of(fixture.team.name()), available.teamName());
        assertEquals(Optional.of(BuiltInTeamRole.MEMBER), available.targetRole());
        assertTrue(available.targetRestricted());
        assertEquals(TeamInvitationPreviewState.EXPIRED, expired.state());
        assertTrue(expired.invitationId().isEmpty());
        assertEquals(TeamInvitationPreviewState.UNAVAILABLE, unknown.state());
        assertTrue(unknown.teamName().isEmpty());
    }

    @Test
    void currentAccountAcceptanceCreatesMembershipAndRoleGrantExactlyOnce() {
        Fixture fixture = new Fixture();
        TeamInvitation invitation = fixture.issue("invite-accept-create");
        int grantsBefore = fixture.repository.grants.size();
        int eventsBefore = fixture.repository.events.size();

        CommandExecution<TeamInvitationAcceptanceResult> accepted = fixture.service.accept(
                fixture.acceptance("invite-accept-1"), TOKEN);
        CommandExecution<TeamInvitationAcceptanceResult> replay = fixture.service.accept(
                fixture.acceptance("invite-accept-1"), TOKEN);

        TeamInvitationAcceptanceResult result = accepted.result().orElseThrow();
        assertEquals(InvitationMembershipDisposition.CREATED, result.membershipDisposition());
        assertTrue(result.roleGrantCreated());
        assertEquals(TeamInvitationStatus.ACCEPTED, result.invitation().status());
        assertEquals(1, fixture.repository.membersFor(fixture.inviteePrincipal.id()).size());
        assertEquals(grantsBefore + 1, fixture.repository.grants.size());
        assertEquals(eventsBefore + 1, fixture.repository.events.size());
        assertTrue(replay.replayed());
        assertEquals(invitation.id(), result.invitation().id());
        assertThrows(
                TeamInvitationApplicationException.class,
                () -> fixture.service.accept(
                        fixture.acceptance("invite-accept-new-key"), TOKEN));
    }

    @Test
    void acceptanceEmailMismatchUsesTheStableInvalidInvitationFailure() {
        Fixture fixture = new Fixture();
        fixture.service.create(
                fixture.command(fixture.admin, "invite-email-mismatch-create"),
                fixture.team.id(),
                new CreateTeamInvitationCommand(
                        Optional.of(new NormalizedEmail("another@example.com")),
                        BuiltInTeamRole.MEMBER,
                        Duration.ofDays(7)));

        TeamInvitationApplicationException failure = assertThrows(
                TeamInvitationApplicationException.class,
                () -> fixture.service.accept(
                        fixture.acceptance("invite-email-mismatch-accept"), TOKEN));

        assertEquals(
                TeamInvitationApplicationFailure.INVALID_INVITATION,
                failure.failure());
        assertTrue(fixture.repository.membersFor(fixture.inviteePrincipal.id()).isEmpty());
    }

    @Test
    void disabledTargetRoleFailsBeforeMembershipMutation() {
        Fixture fixture = new Fixture();
        TeamInvitation invitation = fixture.issue("invite-disabled-role-create");
        TeamRole memberRole = fixture.repository.roles.values().stream()
                .filter(role -> role.isBuiltIn(BuiltInTeamRole.MEMBER))
                .findFirst()
                .orElseThrow();
        fixture.repository.roles.put(
                memberRole.id(),
                memberRole.transitionTo(
                        TeamRoleStatus.DISABLED,
                        UtcTimestamp.parse("2026-08-29T01:01:00Z")));
        int membershipsBefore = fixture.repository.members.size();

        TeamInvitationApplicationException failure = assertThrows(
                TeamInvitationApplicationException.class,
                () -> fixture.service.accept(
                        fixture.acceptance("invite-disabled-role-accept"), TOKEN));

        assertEquals(
                TeamInvitationApplicationFailure.INVALID_INVITATION,
                failure.failure());
        assertEquals(membershipsBefore, fixture.repository.members.size());
        assertEquals(
                TeamInvitationStatus.PENDING,
                fixture.repository.invitations.get(invitation.id()).status());
    }

    private static final class Fixture {
        private final OrganizationId organizationId = OrganizationId.generate();
        private final Principal admin = user(organizationId, "Admin");
        private final Principal member = user(organizationId, "Member");
        private final Principal inviteePrincipal = user(organizationId, "Invitee");
        private final Team team = Team.create(
                TeamId.generate(),
                organizationId,
                "Platform Team",
                TeamMemberId.generate(),
                WorkspaceId.generate(),
                admin.id(),
                NOW);
        private final UserAccount invitee = UserAccount.register(
                UserAccountId.generate(),
                "invitee-" + UUID.randomUUID().toString().substring(0, 8),
                "invitee@example.com",
                "Invitee",
                NOW);
        private final AccountOrganizationBinding binding = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                invitee,
                organizationId,
                inviteePrincipal,
                NOW);
        private final MutableTime time = new MutableTime(NOW);
        private final InMemoryRepository repository = new InMemoryRepository();
        private final TeamInvitationApplicationService service;

        private Fixture() {
            repository.teams.put(team.id(), team);
            TeamRole adminRole = TeamRole.createBuiltIn(
                    TeamRoleId.generate(), team.scope(), BuiltInTeamRole.TEAM_ADMIN, NOW);
            TeamRole memberRole = TeamRole.createBuiltIn(
                    TeamRoleId.generate(), team.scope(), BuiltInTeamRole.MEMBER, NOW);
            repository.roles.put(adminRole.id(), adminRole);
            repository.roles.put(memberRole.id(), memberRole);
            TeamMember adminMembership = team.acceptInvitedMember(
                    TeamMemberId.generate(), admin, admin.id(), NOW);
            TeamMember ordinaryMembership = team.acceptInvitedMember(
                    TeamMemberId.generate(), member, admin.id(), NOW);
            repository.members.put(adminMembership.id(), adminMembership);
            repository.members.put(ordinaryMembership.id(), ordinaryMembership);
            MemberRole adminGrant = MemberRole.grant(
                    MemberRoleId.generate(),
                    adminMembership,
                    adminRole,
                    RoleScope.team(),
                    admin.id(),
                    NOW,
                    NOW,
                    Optional.empty());
            MemberRole memberGrant = MemberRole.grant(
                    MemberRoleId.generate(),
                    ordinaryMembership,
                    memberRole,
                    RoleScope.team(),
                    admin.id(),
                    NOW,
                    NOW,
                    Optional.empty());
            repository.grants.put(adminGrant.id(), adminGrant);
            repository.grants.put(memberGrant.id(), memberGrant);
            TeamInvitationIssueService issueService = new TeamInvitationIssueService(
                    repository, () -> TOKEN, ignored -> DIGEST, repository, time);
            service = new TeamInvitationApplicationService(
                    issueService,
                    token -> token.reveal().equals(TOKEN.reveal()) ? DIGEST : digest(99),
                    repository,
                    repository,
                    repository,
                    repository,
                    repository,
                    new TeamInvitationAcceptanceService(),
                    repository,
                    repository,
                    repository,
                    repository,
                    time);
        }

        private TeamInvitation issue(String key) {
            return service.create(
                            command(admin, key),
                            team.id(),
                            new CreateTeamInvitationCommand(
                                    Optional.of(invitee.normalizedEmail()),
                                    BuiltInTeamRole.MEMBER,
                                    Duration.ofDays(7)))
                    .result()
                    .orElseThrow()
                    .invitation();
        }

        private TeamCommandContext command(Principal actor, String key) {
            return command(actor, key, Optional.empty());
        }

        private TeamCommandContext command(
                Principal actor, String key, Optional<UUID> causationId) {
            return new TeamCommandContext(
                    new TeamAccessContext(actor, false),
                    IdempotencyKey.from(key),
                    UUID.randomUUID(),
                    causationId);
        }

        private AuthenticatedInvitationCommandContext acceptance(String key) {
            return new AuthenticatedInvitationCommandContext(
                    invitee,
                    binding,
                    new TeamAccessContext(inviteePrincipal, false),
                    IdempotencyKey.from(key),
                    UUID.randomUUID(),
                    Optional.empty());
        }
    }

    private static final class InMemoryRepository
            implements TeamInvitationRepository,
                    TeamRepository,
                    TeamMemberRepository,
                    TeamRoleRepository,
                    MemberRoleRepository,
                    DomainEventStore,
                    OutboxRepository,
                    CommandReceiptStore,
                    TransactionExecutor {

        private final Map<TeamInvitationId, TeamInvitation> invitations = new LinkedHashMap<>();
        private final Map<TeamId, Team> teams = new HashMap<>();
        private final Map<TeamMemberId, TeamMember> members = new LinkedHashMap<>();
        private final Map<TeamRoleId, TeamRole> roles = new LinkedHashMap<>();
        private final Map<MemberRoleId, MemberRole> grants = new LinkedHashMap<>();
        private final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
        private final List<PendingOutboxEvent> outbox = new ArrayList<>();
        private final Map<String, ReceiptEntry> receipts = new HashMap<>();

        @Override
        public Optional<TeamInvitation> findById(
                OrganizationId organizationId, TeamInvitationId invitationId) {
            return Optional.ofNullable(invitations.get(invitationId))
                    .filter(value -> value.scope().organizationId().equals(organizationId));
        }

        @Override
        public Optional<TeamInvitation> lockById(
                OrganizationId organizationId, TeamInvitationId invitationId) {
            return findById(organizationId, invitationId);
        }

        @Override
        public Optional<TeamInvitation> findByTokenDigest(InvitationTokenDigest tokenDigest) {
            return invitations.values().stream()
                    .filter(value -> value.tokenDigest().matches(tokenDigest))
                    .findFirst();
        }

        @Override
        public Optional<TeamInvitation> lockByTokenDigest(InvitationTokenDigest tokenDigest) {
            return findByTokenDigest(tokenDigest);
        }

        @Override
        public TeamInvitationPage findByTeam(
                OrganizationId organizationId,
                TeamId teamId,
                Optional<TeamInvitationCursor> cursor,
                int limit) {
            List<TeamInvitation> page = invitations.values().stream()
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.scope().teamId().equals(teamId))
                    .sorted(Comparator.comparing(
                                    (TeamInvitation value) -> value.lifecycle().createdAt())
                            .thenComparing(value -> value.id().toString())
                            .reversed())
                    .limit(limit)
                    .toList();
            return new TeamInvitationPage(page, Optional.empty());
        }

        @Override
        public List<TeamInvitation> lockExpiredBatch(UtcTimestamp now, int limit) {
            return List.of();
        }

        @Override
        public TeamInvitation create(TeamInvitation invitation) {
            if (findByTokenDigest(invitation.tokenDigest()).isPresent()) {
                throw new TeamInvitationConflictException();
            }
            invitations.put(invitation.id(), invitation);
            return invitation;
        }

        @Override
        public TeamInvitation update(TeamInvitation invitation, long expectedVersion) {
            TeamInvitation current = invitations.get(invitation.id());
            if (current == null || current.version() != expectedVersion) {
                throw new IllegalStateException("unexpected invitation version");
            }
            invitations.put(invitation.id(), invitation);
            return invitation;
        }

        @Override
        public Team create(Team team) {
            teams.put(team.id(), team);
            return team;
        }

        @Override
        public Optional<Team> findById(OrganizationId organizationId, TeamId id) {
            return Optional.ofNullable(teams.get(id))
                    .filter(value -> value.organizationId().equals(organizationId));
        }

        @Override
        public Optional<Team> lockById(OrganizationId organizationId, TeamId id) {
            return findById(organizationId, id);
        }

        @Override
        public TeamMember create(TeamMember member) {
            members.put(member.id(), member);
            return member;
        }

        @Override
        public TeamMember update(TeamMember member) {
            members.put(member.id(), member);
            return member;
        }

        @Override
        public Optional<TeamMember> findByTeamAndUserPrincipalId(
                OrganizationId organizationId, TeamId teamId, PrincipalId userPrincipalId) {
            return members.values().stream()
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.scope().teamId().equals(teamId))
                    .filter(value -> value.userPrincipalId().equals(userPrincipalId))
                    .findFirst();
        }

        private List<TeamMember> membersFor(PrincipalId principalId) {
            return members.values().stream()
                    .filter(value -> value.userPrincipalId().equals(principalId))
                    .toList();
        }

        @Override
        public List<TeamRole> createAll(List<TeamRole> values) {
            values.forEach(value -> roles.put(value.id(), value));
            return List.copyOf(values);
        }

        @Override
        public List<TeamRole> findByTeam(OrganizationId organizationId, TeamId teamId) {
            return roles.values().stream()
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.scope().teamId().equals(teamId))
                    .toList();
        }

        @Override
        public MemberRole create(MemberRole memberRole) {
            grants.put(memberRole.id(), memberRole);
            return memberRole;
        }

        @Override
        public MemberRole update(MemberRole memberRole) {
            grants.put(memberRole.id(), memberRole);
            return memberRole;
        }

        @Override
        public List<MemberRole> findByMember(
                OrganizationId organizationId, TeamMemberId memberId) {
            return grants.values().stream()
                    .filter(value -> value.teamScope().organizationId().equals(organizationId))
                    .filter(value -> value.teamMemberId().equals(memberId))
                    .toList();
        }

        @Override
        public void append(DomainEventEnvelope<? extends DomainEvent> event) {
            events.add(event);
        }

        @Override
        public void enqueue(PendingOutboxEvent event) {
            outbox.add(event);
        }

        @Override
        public CommandReservation reserve(CommandReservationRequest request) {
            String key = request.organizationId() + ":" + request.idempotencyKey().value();
            ReceiptEntry existing = receipts.get(key);
            if (existing == null) {
                receipts.put(key, new ReceiptEntry(request, null));
                return CommandReservation.newlyAcquired();
            }
            if (!existing.request.commandType().equals(request.commandType())
                    || !existing.request.requestHash().equals(request.requestHash())) {
                throw new IdempotencyConflictException(
                        request.idempotencyKey().value(),
                        existing.request.requestHash().value(),
                        request.requestHash().value());
            }
            return CommandReservation.replay(existing.receipt);
        }

        @Override
        public void complete(
                OrganizationId organizationId,
                IdempotencyKey idempotencyKey,
                CommandReceipt receipt,
                UtcTimestamp completedAt) {
            String key = organizationId + ":" + idempotencyKey.value();
            ReceiptEntry existing = receipts.get(key);
            receipts.put(key, new ReceiptEntry(existing.request, receipt));
        }

        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }

        private record ReceiptEntry(CommandReservationRequest request, CommandReceipt receipt) {}
    }

    private static final class MutableTime implements TimeProvider {
        private UtcTimestamp now;

        private MutableTime(UtcTimestamp now) {
            this.now = now;
        }

        @Override
        public UtcTimestamp now() {
            return now;
        }
    }

    private static Principal user(OrganizationId organizationId, String name) {
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
