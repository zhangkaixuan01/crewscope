package io.crewscope.application.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
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
import io.crewscope.domain.team.TeamMemberId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Fast application-contract proof for M7-I06 issuance, redaction and bounded expiry. */
class TeamInvitationInfrastructureContractM7I06Test {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-28T12:00:00Z"));

    @Test
    void issuanceReturnsPlaintextOnceAndPersistsOnlyItsDigest() {
        InMemoryInvitationRepository repository = new InMemoryInvitationRepository();
        InvitationToken token = token(7);
        InvitationTokenDigest digest = digest(7);
        TeamFixture fixture = fixture();
        TeamInvitationIssueService service = new TeamInvitationIssueService(
                repository,
                () -> token,
                candidate -> candidate == token ? digest : digest(99),
                directTransactions(),
                () -> NOW);

        TeamInvitationIssueResult result = service.issue(
                fixture.team(),
                fixture.inviter(),
                Optional.of(new NormalizedEmail("person@example.com")),
                BuiltInTeamRole.MEMBER,
                Duration.ofDays(7));

        TeamInvitation persisted = repository.findByTokenDigest(digest).orElseThrow();
        assertEquals(result.invitation().id(), persisted.id());
        assertEquals(token.reveal(), result.revealToken());
        assertEquals("[REDACTED]", token.toString());
        assertFalse(result.toString().contains(token.reveal()));
        assertTrue(persisted.tokenDigest().matches(digest));
        assertEquals(NOW.value().plus(Duration.ofDays(7)), persisted.expiresAt().value());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.issue(
                        fixture.team(),
                        fixture.inviter(),
                        Optional.empty(),
                        BuiltInTeamRole.MEMBER,
                        Duration.ofSeconds(59)));
    }

    @Test
    void expiryClosesOnlyOneBoundedPendingBatchAndKeepsEveryInvitation() {
        InMemoryInvitationRepository repository = new InMemoryInvitationRepository();
        TeamFixture fixture = fixture();
        for (int index = 0; index < 3; index++) {
            UtcTimestamp created = UtcTimestamp.from(NOW.value().minus(Duration.ofHours(index + 2L)));
            repository.create(TeamInvitation.issue(
                    TeamInvitationId.generate(),
                    fixture.team(),
                    fixture.inviter(),
                    Optional.empty(),
                    BuiltInTeamRole.MEMBER,
                    digest(index),
                    UtcTimestamp.from(NOW.value().minus(Duration.ofHours(1))),
                    created));
        }
        repository.create(TeamInvitation.issue(
                TeamInvitationId.generate(),
                fixture.team(),
                fixture.inviter(),
                Optional.empty(),
                BuiltInTeamRole.MEMBER,
                digest(9),
                UtcTimestamp.from(NOW.value().plus(Duration.ofDays(1))),
                NOW));
        TeamInvitationExpiryService service = new TeamInvitationExpiryService(
                repository, directTransactions(), () -> NOW);

        TeamInvitationExpiryResult first = service.expireDue(2);
        TeamInvitationExpiryResult second = service.expireDue(2);

        assertEquals(2, first.expiredInvitations());
        assertTrue(first.capacityLimited());
        assertEquals(1, second.expiredInvitations());
        assertFalse(second.capacityLimited());
        assertEquals(4, repository.size());
        assertEquals(3, repository.count(TeamInvitationStatus.EXPIRED));
        assertEquals(1, repository.count(TeamInvitationStatus.PENDING));
        assertThrows(IllegalArgumentException.class, () -> service.expireDue(0));
    }

    @Test
    void tokenRequiresCanonical256BitBase64Url() {
        assertEquals(InvitationToken.ENCODED_LENGTH, token(1).reveal().length());
        assertThrows(DomainValidationException.class, () -> new InvitationToken("a".repeat(42)));
        assertThrows(DomainValidationException.class, () -> new InvitationToken("a".repeat(43)));
        assertThrows(DomainValidationException.class, () -> new InvitationToken("+".repeat(43)));
    }

    private static TransactionExecutor directTransactions() {
        return new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };
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

    private static TeamFixture fixture() {
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
                "Team",
                TeamMemberId.generate(),
                WorkspaceId.generate(),
                inviter.id(),
                NOW);
        return new TeamFixture(team, inviter);
    }

    private record TeamFixture(Team team, Principal inviter) {}

    private static final class InMemoryInvitationRepository implements TeamInvitationRepository {
        private final Map<TeamInvitationId, TeamInvitation> values = new HashMap<>();

        @Override
        public synchronized Optional<TeamInvitation> findById(
                OrganizationId organizationId, TeamInvitationId invitationId) {
            return Optional.ofNullable(values.get(invitationId))
                    .filter(value -> value.scope().organizationId().equals(organizationId));
        }

        @Override
        public synchronized Optional<TeamInvitation> lockById(
                OrganizationId organizationId, TeamInvitationId invitationId) {
            return findById(organizationId, invitationId);
        }

        @Override
        public synchronized Optional<TeamInvitation> findByTokenDigest(
                InvitationTokenDigest tokenDigest) {
            return values.values().stream()
                    .filter(value -> value.tokenDigest().matches(tokenDigest))
                    .findFirst();
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
            List<TeamInvitation> sorted = values.values().stream()
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.scope().teamId().equals(teamId))
                    .sorted(Comparator.comparing(
                                    (TeamInvitation value) -> value.lifecycle().createdAt())
                            .thenComparing(value -> value.id().toString())
                            .reversed())
                    .limit(limit)
                    .toList();
            return new TeamInvitationPage(sorted, Optional.empty());
        }

        @Override
        public synchronized List<TeamInvitation> lockExpiredBatch(UtcTimestamp now, int limit) {
            return values.values().stream()
                    .filter(value -> value.status() == TeamInvitationStatus.PENDING)
                    .filter(value -> !value.isPendingAt(now))
                    .sorted(Comparator.comparing(TeamInvitation::expiresAt))
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized TeamInvitation create(TeamInvitation invitation) {
            if (values.containsKey(invitation.id())
                    || findByTokenDigest(invitation.tokenDigest()).isPresent()) {
                throw new TeamInvitationConflictException();
            }
            values.put(invitation.id(), invitation);
            return invitation;
        }

        @Override
        public synchronized TeamInvitation update(
                TeamInvitation invitation, long expectedVersion) {
            TeamInvitation current = values.get(invitation.id());
            if (current == null || current.version() != expectedVersion) {
                throw new IllegalStateException("fixture version conflict");
            }
            values.put(invitation.id(), invitation);
            return invitation;
        }

        synchronized int size() {
            return values.size();
        }

        synchronized long count(TeamInvitationStatus status) {
            return values.values().stream().filter(value -> value.status() == status).count();
        }
    }
}
