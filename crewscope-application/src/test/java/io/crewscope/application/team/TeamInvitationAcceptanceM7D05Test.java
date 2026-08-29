package io.crewscope.application.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountOrganizationBindingStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.InvitationTokenDigest;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationConflictException;
import io.crewscope.domain.team.TeamInvitationId;
import io.crewscope.domain.team.TeamInvitationStatus;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.team.TeamScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TeamInvitationAcceptanceM7D05Test {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-28T09:00:00Z"));
    private static final UtcTimestamp ACCEPTED_AT =
            UtcTimestamp.from(Instant.parse("2026-08-28T10:00:00Z"));
    private static final UtcTimestamp EXPIRY =
            UtcTimestamp.from(Instant.parse("2026-09-04T09:00:00Z"));
    private static final InvitationTokenDigest DIGEST = digest(1);

    private final TeamInvitationAcceptanceService service =
            new TeamInvitationAcceptanceService();

    @Test
    void createsOneActiveInvitationMembershipAndAcceptanceResult() {
        Fixture fixture = new Fixture();

        TeamInvitationAcceptancePlan plan = fixture.plan(Optional.empty());

        assertEquals(InvitationMembershipDisposition.CREATED, plan.membershipDisposition());
        assertTrue(plan.createsMembership());
        assertFalse(plan.updatesMembership());
        assertEquals(TeamMemberStatus.ACTIVE, plan.membership().status());
        assertEquals(io.crewscope.domain.team.TeamJoinMethod.INVITATION, plan.membership().joinMethod());
        assertEquals(
                Optional.of(fixture.inviter.id()),
                plan.membership().invitedByPrincipalId());
        assertEquals(0, plan.membership().version());
        assertEquals(
                Optional.of(plan.membership().id()),
                plan.invitation().acceptedMemberId());
        assertEquals(Optional.of(fixture.account.id()), plan.invitation().acceptedByAccountId());
        assertEquals(BuiltInTeamRole.MEMBER, plan.targetRole());
    }

    @Test
    void reusesAnExistingActiveMembershipWithoutMutation() {
        Fixture fixture = new Fixture();
        TeamMember existing = fixture.team.acceptInvitedMember(
                TeamMemberId.generate(),
                fixture.userPrincipal,
                fixture.inviter.id(),
                NOW);

        TeamInvitationAcceptancePlan plan = fixture.plan(Optional.of(existing));

        assertSame(existing, plan.membership());
        assertEquals(InvitationMembershipDisposition.REUSED, plan.membershipDisposition());
        assertFalse(plan.createsMembership());
        assertFalse(plan.updatesMembership());
    }

    @Test
    void activatesEligibleHistoricalMembershipsWhilePreservingStableIds() {
        Fixture fixture = new Fixture();
        TeamMember pending = fixture.team.inviteMember(
                TeamMemberId.generate(),
                fixture.userPrincipal,
                fixture.inviter.id(),
                NOW);
        TeamMember active = fixture.team.acceptInvitedMember(
                TeamMemberId.generate(),
                fixture.userPrincipal,
                fixture.inviter.id(),
                NOW);
        List<TeamMember> historical = List.of(
                pending,
                active.leave(ACCEPTED_AT),
                active.remove(ACCEPTED_AT));

        for (TeamMember existing : historical) {
            TeamInvitationAcceptancePlan plan = fixture.plan(Optional.of(existing));
            assertEquals(existing.id(), plan.membership().id());
            assertEquals(TeamMemberStatus.ACTIVE, plan.membership().status());
            assertEquals(
                    InvitationMembershipDisposition.ACTIVATED,
                    plan.membershipDisposition());
            assertTrue(plan.updatesMembership());
        }
    }

    @Test
    void suspendedMembershipRequiresSeparateAdministratorReactivation() {
        Fixture fixture = new Fixture();
        TeamMember suspended = fixture.team.acceptInvitedMember(
                        TeamMemberId.generate(),
                        fixture.userPrincipal,
                        fixture.inviter.id(),
                        NOW)
                .suspend(ACCEPTED_AT);

        assertThrows(
                DomainValidationException.class,
                () -> fixture.plan(Optional.of(suspended)));
    }

    @Test
    void pendingMembershipFromAnotherInviterCannotBeConsumed() {
        Fixture fixture = new Fixture();
        TeamMember pending = fixture.team.inviteMember(
                TeamMemberId.generate(),
                fixture.userPrincipal,
                user(fixture.organizationId).id(),
                NOW);

        assertThrows(
                DomainValidationException.class,
                () -> fixture.plan(Optional.of(pending)));
    }

    @Test
    void existingMembershipMustMatchTeamAndBoundPrincipal() {
        Fixture fixture = new Fixture();
        Team otherTeam = team(fixture.organizationId, fixture.inviter.id());
        TeamMember crossTeam = otherTeam.acceptInvitedMember(
                TeamMemberId.generate(),
                fixture.userPrincipal,
                fixture.inviter.id(),
                NOW);
        Principal anotherPrincipal = user(fixture.organizationId);
        TeamMember otherPrincipal = fixture.team.acceptInvitedMember(
                TeamMemberId.generate(), anotherPrincipal, fixture.inviter.id(), NOW);

        assertThrows(
                DomainValidationException.class,
                () -> fixture.plan(Optional.of(crossTeam)));
        assertThrows(
                DomainValidationException.class,
                () -> fixture.plan(Optional.of(otherPrincipal)));
    }

    @Test
    void bindingMustResolveToTheSuppliedActiveUserPrincipal() {
        Fixture fixture = new Fixture();
        Principal anotherPrincipal = user(fixture.organizationId);
        AccountOrganizationBinding incompatible = AccountOrganizationBinding.reconstitute(
                fixture.binding.id(),
                fixture.account.id(),
                fixture.organizationId,
                anotherPrincipal.id(),
                AccountOrganizationBindingStatus.ACTIVE,
                0,
                LifecycleMetadata.createdAt(NOW));

        assertThrows(
                DomainValidationException.class,
                () -> service.planAcceptance(
                        fixture.invitation,
                        DIGEST,
                        fixture.account,
                        incompatible,
                        fixture.team,
                        fixture.userPrincipal,
                        Optional.empty(),
                        TeamMemberId.generate(),
                        ACCEPTED_AT));
    }

    @Test
    void concurrentAcceptanceCommitsExactlyOneTerminalInvitation() throws Exception {
        Fixture fixture = new Fixture();
        TeamMember existing = fixture.team.acceptInvitedMember(
                TeamMemberId.generate(),
                fixture.userPrincipal,
                fixture.inviter.id(),
                NOW);
        InMemoryInvitationRepository repository = new InMemoryInvitationRepository();
        repository.create(fixture.invitation);
        int callers = 16;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        try {
            List<Future<Object>> futures = IntStream.range(0, callers)
                    .mapToObj(index -> executor.submit(() -> {
                        TeamInvitation loaded = repository
                                .findByTokenDigest(DIGEST)
                                .orElseThrow();
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        TeamInvitationAcceptancePlan plan = service.planAcceptance(
                                loaded,
                                DIGEST,
                                fixture.account,
                                fixture.binding,
                                fixture.team,
                                fixture.userPrincipal,
                                Optional.of(existing),
                                TeamMemberId.generate(),
                                ACCEPTED_AT);
                        try {
                            repository.update(plan.invitation(), loaded.version());
                            committed.incrementAndGet();
                        } catch (OptimisticLockConflictException conflict) {
                            conflicted.incrementAndGet();
                        }
                        return null;
                    }))
                    .toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            assertEquals(1, committed.get());
            assertEquals(callers - 1, conflicted.get());
            TeamInvitation accepted = repository.findByTokenDigest(DIGEST).orElseThrow();
            assertEquals(io.crewscope.domain.team.TeamInvitationStatus.ACCEPTED, accepted.status());
            assertEquals(Optional.of(existing.id()), accepted.acceptedMemberId());
            assertThrows(
                    io.crewscope.domain.shared.error.InvalidStateTransitionException.class,
                    () -> service.planAcceptance(
                            accepted,
                            DIGEST,
                            fixture.account,
                            fixture.binding,
                            fixture.team,
                            fixture.userPrincipal,
                            Optional.of(existing),
                            TeamMemberId.generate(),
                            ACCEPTED_AT));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void repositoryDigestCollisionUsesOneSafeConflict() {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        InMemoryInvitationRepository repository = new InMemoryInvitationRepository();
        repository.create(first.invitation);

        assertThrows(
                TeamInvitationConflictException.class,
                () -> repository.create(second.invitation));
        assertEquals(
                1,
                repository.findByTeam(
                                first.organizationId,
                                first.team.id(),
                                Optional.empty(),
                                10)
                        .invitations()
                        .size());
    }

    private final class Fixture {
        private final OrganizationId organizationId = OrganizationId.generate();
        private final Principal inviter = user(organizationId);
        private final Team team = team(organizationId, inviter.id());
        private final UserAccount account = UserAccount.register(
                UserAccountId.generate(),
                "invitee-" + accountSuffix(),
                accountSuffix() + "@example.com",
                "Invitee",
                NOW);
        private final Principal userPrincipal = user(organizationId);
        private final AccountOrganizationBinding binding = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                organizationId,
                userPrincipal,
                NOW);
        private final TeamInvitation invitation = TeamInvitation.issue(
                TeamInvitationId.generate(),
                team,
                inviter,
                Optional.of(account.normalizedEmail()),
                BuiltInTeamRole.MEMBER,
                DIGEST,
                EXPIRY,
                NOW);

        private TeamInvitationAcceptancePlan plan(Optional<TeamMember> existing) {
            return service.planAcceptance(
                    invitation,
                    DIGEST,
                    account,
                    binding,
                    team,
                    userPrincipal,
                    existing,
                    TeamMemberId.generate(),
                    ACCEPTED_AT);
        }
    }

    private static final class InMemoryInvitationRepository implements TeamInvitationRepository {
        private final Map<TeamInvitationId, TeamInvitation> invitations = new HashMap<>();
        private final Map<InvitationTokenDigest, TeamInvitationId> byDigest = new HashMap<>();

        @Override
        public synchronized Optional<TeamInvitation> findById(
                OrganizationId organizationId, TeamInvitationId invitationId) {
            TeamInvitation invitation = invitations.get(invitationId);
            if (invitation == null
                    || !invitation.scope().organizationId().equals(organizationId)) {
                return Optional.empty();
            }
            return Optional.of(invitation);
        }

        @Override
        public synchronized Optional<TeamInvitation> lockById(
                OrganizationId organizationId, TeamInvitationId invitationId) {
            return findById(organizationId, invitationId);
        }

        @Override
        public synchronized Optional<TeamInvitation> findByTokenDigest(
                InvitationTokenDigest tokenDigest) {
            return Optional.ofNullable(byDigest.get(tokenDigest)).map(invitations::get);
        }

        @Override
        public synchronized Optional<TeamInvitation> lockByTokenDigest(
                InvitationTokenDigest tokenDigest) {
            return findByTokenDigest(tokenDigest);
        }

        @Override
        public synchronized TeamInvitationPage findByTeam(
                OrganizationId organizationId,
                TeamId teamId,
                Optional<TeamInvitationCursor> cursor,
                int limit) {
            List<TeamInvitation> result = new ArrayList<>();
            for (TeamInvitation invitation : invitations.values()) {
                if (invitation.scope().organizationId().equals(organizationId)
                        && invitation.scope().teamId().equals(teamId)) {
                    result.add(invitation);
                }
            }
            return new TeamInvitationPage(List.copyOf(result), Optional.empty());
        }

        @Override
        public synchronized List<TeamInvitation> lockExpiredBatch(
                UtcTimestamp now, int limit) {
            return invitations.values().stream()
                    .filter(invitation -> !invitation.isPendingAt(now))
                    .filter(invitation -> invitation.status() == TeamInvitationStatus.PENDING)
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized TeamInvitation create(TeamInvitation invitation) {
            if (invitations.containsKey(invitation.id())
                    || byDigest.containsKey(invitation.tokenDigest())) {
                throw new TeamInvitationConflictException();
            }
            invitations.put(invitation.id(), invitation);
            byDigest.put(invitation.tokenDigest(), invitation.id());
            return invitation;
        }

        @Override
        public synchronized TeamInvitation update(
                TeamInvitation invitation, long expectedVersion) {
            TeamInvitation current = invitations.get(invitation.id());
            if (current == null || current.version() != expectedVersion) {
                throw new OptimisticLockConflictException(
                        "TeamInvitation",
                        invitation.id(),
                        expectedVersion,
                        current == null ? 0 : current.version());
            }
            TeamInvitationId digestOwner = byDigest.get(invitation.tokenDigest());
            if (digestOwner != null && !digestOwner.equals(invitation.id())) {
                throw new TeamInvitationConflictException();
            }
            invitations.put(invitation.id(), invitation);
            return invitation;
        }
    }

    private static Team team(OrganizationId organizationId, PrincipalId actor) {
        return Team.create(
                TeamId.generate(),
                organizationId,
                "Team",
                TeamMemberId.generate(),
                WorkspaceId.generate(),
                actor,
                NOW);
    }

    private static Principal user(OrganizationId organizationId) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "User",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }

    private static InvitationTokenDigest digest(int seed) {
        byte[] value = new byte[InvitationTokenDigest.BYTE_LENGTH];
        value[0] = (byte) seed;
        return InvitationTokenDigest.fromBytes(value);
    }

    private static String accountSuffix() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
