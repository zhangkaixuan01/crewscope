package io.crewscope.infrastructure.persistence.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.team.InvitationTokenDigester;
import io.crewscope.application.team.TeamInvitationCursor;
import io.crewscope.application.team.TeamInvitationExpiryResult;
import io.crewscope.application.team.TeamInvitationExpiryService;
import io.crewscope.application.team.TeamInvitationIssueResult;
import io.crewscope.application.team.TeamInvitationIssueService;
import io.crewscope.application.team.TeamInvitationPage;
import io.crewscope.application.team.TeamInvitationRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
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
import io.crewscope.domain.team.TeamInvitationId;
import io.crewscope.domain.team.TeamInvitationStatus;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.infrastructure.security.invitation.HmacSha256InvitationTokenDigester;
import io.crewscope.infrastructure.security.invitation.SecureInvitationTokenGenerator;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL proof for M7-I06 digest-only issuance, row claims, paging and durable expiry. */
@SpringBootTest(
        classes = JdbcTeamInvitationRepositoryM7I06IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false"
        })
class JdbcTeamInvitationRepositoryM7I06IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-28T12:00:00Z"));
    private static final UtcTimestamp WEEK_LATER =
            UtcTimestamp.from(NOW.value().plus(Duration.ofDays(7)));

    @Autowired private TeamInvitationRepository invitations;
    @Autowired private TransactionExecutor transactions;
    @Autowired private JdbcTemplate jdbc;

    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void issueReturnsOnePlaintextTokenButDatabaseAndRepositoryRetainOnlyItsDigest() {
        Fixture fixture = fixture();
        byte[] key = new byte[32];
        key[0] = 41;
        InvitationTokenDigester digester = new HmacSha256InvitationTokenDigester(
                Base64.getEncoder().encodeToString(key));
        TeamInvitationIssueService service = new TeamInvitationIssueService(
                invitations,
                new SecureInvitationTokenGenerator(),
                digester,
                transactions,
                () -> NOW);

        TeamInvitationIssueResult result = service.issue(
                fixture.team(),
                fixture.inviter(),
                Optional.empty(),
                BuiltInTeamRole.MEMBER,
                Duration.ofDays(7));

        String plaintext = result.revealToken();
        InvitationTokenDigest digest = digester.digest(result.token());
        String storedDigest = jdbc.queryForObject(
                "SELECT token_digest FROM crewscope.team_invitation WHERE id = ?",
                String.class,
                result.invitation().id().value());
        assertEquals(digest.valueForPersistence(), storedDigest);
        assertNotEquals(plaintext, storedDigest);
        assertFalse(storedDigest.contains(plaintext));
        assertFalse(result.toString().contains(plaintext));
        assertEquals(
                result.invitation().id(),
                invitations.findByTokenDigest(digest).orElseThrow().id());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.team_invitation"
                                + " WHERE token_digest LIKE '%' || ? || '%'",
                        Integer.class,
                        plaintext));
    }

    @Test
    void repositoryRoundTripsTerminalStatesAndRejectsStaleVersionUpdates() {
        Fixture fixture = fixture();
        TeamInvitation revocable = createInvitation(
                fixture, 1, NOW, WEEK_LATER);
        TeamInvitation due = createInvitation(
                fixture,
                2,
                UtcTimestamp.from(NOW.value().minus(Duration.ofDays(2))),
                NOW);

        TeamInvitation revoked = invitations.update(revocable.revoke(NOW), revocable.version());
        TeamInvitation expired = invitations.update(due.expire(NOW), due.version());

        assertEquals(TeamInvitationStatus.REVOKED, revoked.status());
        assertEquals(TeamInvitationStatus.EXPIRED, expired.status());
        assertEquals(NOW, revoked.resolvedAt().orElseThrow());
        assertEquals(NOW, expired.resolvedAt().orElseThrow());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> invitations.update(revocable.revoke(NOW), 0));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.team_invitation", Integer.class));
    }

    @Test
    void keysetPagesAreBoundedNewestFirstWithoutDuplicatesOrDigestLoss() {
        Fixture fixture = fixture();
        List<TeamInvitation> expected = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            UtcTimestamp created = UtcTimestamp.from(NOW.value().plusSeconds(index));
            expected.add(createInvitation(
                    fixture,
                    10 + index,
                    created,
                    UtcTimestamp.from(created.value().plus(Duration.ofDays(1)))));
        }
        Collections.reverse(expected);

        List<TeamInvitation> actual = new ArrayList<>();
        Optional<TeamInvitationCursor> cursor = Optional.empty();
        do {
            TeamInvitationPage page = invitations.findByTeam(
                    fixture.organizationId(), fixture.team().id(), cursor, 2);
            actual.addAll(page.invitations());
            cursor = page.nextCursor();
        } while (cursor.isPresent());

        assertEquals(
                expected.stream().map(TeamInvitation::id).toList(),
                actual.stream().map(TeamInvitation::id).toList());
        assertEquals(5, new HashSet<>(actual.stream().map(TeamInvitation::id).toList()).size());
        assertTrue(actual.stream().allMatch(value -> invitations
                .findByTokenDigest(value.tokenDigest())
                .filter(found -> found.id().equals(value.id()))
                .isPresent()));
        assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> invitations.findByTeam(
                        fixture.organizationId(), fixture.team().id(), Optional.empty(), 201));
    }

    @Test
    void concurrentTokenClaimsSerializeToExactlyOneTerminalWinner() throws Exception {
        Fixture fixture = fixture();
        TeamInvitation invitation = createInvitation(fixture, 31, NOW, WEEK_LATER);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return transactions.required(() -> {
                    TeamInvitation claimed = invitations
                            .lockByTokenDigest(invitation.tokenDigest())
                            .orElseThrow();
                    if (claimed.status() != TeamInvitationStatus.PENDING) {
                        return false;
                    }
                    invitations.update(claimed.revoke(NOW), claimed.version());
                    return true;
                });
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int winners = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(20, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        assertEquals(1, winners);
        assertEquals(
                TeamInvitationStatus.REVOKED,
                invitations.findByTokenDigest(invitation.tokenDigest()).orElseThrow().status());
    }

    @Test
    void managementAndTokenClaimsUseTheSameInvitationRowLock() throws Exception {
        Fixture fixture = fixture();
        TeamInvitation invitation = createInvitation(fixture, 35, NOW, WEEK_LATER);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);

        Future<Boolean> management = executor.submit(() -> transactions.required(() -> {
            TeamInvitation claimed = invitations
                    .lockById(fixture.organizationId(), invitation.id())
                    .orElseThrow();
            firstLocked.countDown();
            await(releaseFirst);
            invitations.update(claimed.revoke(NOW), claimed.version());
            return true;
        }));
        assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
        Future<Boolean> tokenClaim = executor.submit(() -> transactions.required(() -> {
            secondAttempted.countDown();
            TeamInvitation claimed = invitations
                    .lockByTokenDigest(invitation.tokenDigest())
                    .orElseThrow();
            secondCompleted.countDown();
            if (claimed.status() != TeamInvitationStatus.PENDING) {
                return false;
            }
            invitations.update(claimed.revoke(NOW), claimed.version());
            return true;
        }));
        assertTrue(secondAttempted.await(10, TimeUnit.SECONDS));
        assertFalse(secondCompleted.await(250, TimeUnit.MILLISECONDS));

        releaseFirst.countDown();
        assertTrue(management.get(20, TimeUnit.SECONDS));
        assertFalse(tokenClaim.get(20, TimeUnit.SECONDS));
        assertEquals(0, secondCompleted.getCount());
        assertEquals(
                TeamInvitationStatus.REVOKED,
                invitations.findById(fixture.organizationId(), invitation.id()).orElseThrow().status());
    }

    @Test
    void boundedExpiryUsesSkipLockedAndNeverDeletesInvitationOrAuditFacts() throws Exception {
        Fixture fixture = fixture();
        insertAuditSourceFact(fixture);
        for (int index = 0; index < 3; index++) {
            createInvitation(
                    fixture,
                    40 + index,
                    UtcTimestamp.from(NOW.value().minus(Duration.ofDays(2))),
                    NOW);
        }
        createInvitation(fixture, 49, NOW, WEEK_LATER);
        TeamInvitationExpiryService service =
                new TeamInvitationExpiryService(invitations, transactions, () -> NOW);

        TeamInvitationExpiryResult first = service.expireDue(2);
        TeamInvitationExpiryResult second = service.expireDue(2);

        assertEquals(2, first.expiredInvitations());
        assertTrue(first.capacityLimited());
        assertEquals(1, second.expiredInvitations());
        assertFalse(second.capacityLimited());
        assertEquals(
                4,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.team_invitation", Integer.class));
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.team_invitation WHERE status = 'EXPIRED'",
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM crewscope.domain_event", Integer.class));
        assertEquals(
                Set.of("PENDING", "EXPIRED"),
                new HashSet<>(jdbc.queryForList(
                        "SELECT DISTINCT status FROM crewscope.team_invitation", String.class)));
    }

    private TeamInvitation createInvitation(
            Fixture fixture,
            int digestSeed,
            UtcTimestamp createdAt,
            UtcTimestamp expiresAt) {
        return invitations.create(TeamInvitation.issue(
                TeamInvitationId.generate(),
                fixture.team(),
                fixture.inviter(),
                Optional.empty(),
                BuiltInTeamRole.MEMBER,
                digest(digestSeed),
                expiresAt,
                createdAt));
    }

    private Fixture fixture() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal inviter = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Invitation Owner",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        Team team = Team.create(
                TeamId.generate(),
                organizationId,
                "Invitation Team",
                TeamMemberId.generate(),
                WorkspaceId.generate(),
                inviter.id(),
                NOW);
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status)"
                        + " VALUES (?, 'Invitation Organization', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status)"
                        + " VALUES (?, ?, 'Invitation Team', 'ACTIVE')",
                team.id().value(),
                organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    display_name, visibility, status
                ) VALUES (?, ?, NULL, 'USER', 'Invitation Owner', 'ORGANIZATION', 'ACTIVE')
                """,
                inviter.id().value(),
                organizationId.value());
        return new Fixture(organizationId, team, inviter);
    }

    private void insertAuditSourceFact(Fixture fixture) {
        jdbc.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id, team_id,
                    subject_type, subject_id, actor_type, actor_id, correlation_id,
                    occurred_at, payload, aggregate_version
                ) VALUES (?, 'TeamInvitationCreated', 'V1', ?, ?,
                    'TeamInvitation', ?, 'USER', ?, ?, ?, '{}'::JSONB, 0)
                """,
                UUID.randomUUID(),
                fixture.organizationId().value(),
                fixture.team().id().value(),
                UUID.randomUUID(),
                fixture.inviter().id().value(),
                UUID.randomUUID(),
                NOW.toOffsetDateTime());
    }

    private static InvitationTokenDigest digest(int seed) {
        byte[] value = new byte[InvitationTokenDigest.BYTE_LENGTH];
        value[0] = (byte) seed;
        return InvitationTokenDigest.fromBytes(value);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for invitation lock test");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("invitation lock test was interrupted", interrupted);
        }
    }

    private record Fixture(
            OrganizationId organizationId, Team team, Principal inviter) {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        TeamInvitationPersistenceMapper.class,
        JdbcTeamInvitationRepositoryAdapter.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
