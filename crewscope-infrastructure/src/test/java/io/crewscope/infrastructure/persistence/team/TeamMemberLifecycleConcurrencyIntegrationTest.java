package io.crewscope.infrastructure.persistence.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberLifecycleApplicationService;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.LastOwnerProtectionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.infrastructure.persistence.command.JdbcCommandReceiptStore;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL proof for ADR-038 §1: the Team row lock plus countEffectiveOwners serialize
 * concurrent Owner exits, If-Match CAS rejects racing suspensions, the authorization dimension
 * stays monotonic across interleaved writes, and one Team's revocation never leaks into another.
 */
@SpringBootTest(
        classes = TeamMemberLifecycleConcurrencyIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false"
        })
class TeamMemberLifecycleConcurrencyIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-09-09T07:00:00Z"));

    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private TeamRoleRepository roleRepository;
    @Autowired private MemberRoleRepository grants;
    @Autowired private PrincipalRepository principals;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private DomainEventStore eventStore;
    @Autowired private OutboxRepository outbox;
    @Autowired private CommandReceiptStore receipts;
    @Autowired private TransactionExecutor transactions;
    @Autowired private JdbcTemplate jdbc;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final TimeProvider time = () -> NOW;
    private TeamMemberLifecycleApplicationService service;

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        service =
                new TeamMemberLifecycleApplicationService(
                        teams,
                        members,
                        (TeamMembershipQuery) members,
                        roleRepository,
                        grants,
                        principals,
                        eventStore,
                        outbox,
                        receipts,
                        transactions,
                        time);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void concurrentOwnerExitsSerializeSoExactlyOneSucceeds() throws Exception {
        Fixture fixture = fixture("Owner Exit Team");
        Principal secondUser = addUser(fixture, "Second Owner");
        TeamMember secondOwner = addMember(fixture, secondUser, BuiltInTeamRole.MEMBER);
        TeamRole ownerRole = roleByKey(fixture, BuiltInTeamRole.TEAM_OWNER);
        // Two effective Owners exist as a raw database fact — grantOwner's domain rule only
        // allows the Team's ownerMemberId, so this row is seeded directly. The Team lock and
        // countEffectiveOwners must keep both from removing the last authority at once.
        jdbc.update(
                """
                INSERT INTO crewscope.team_member_role (
                    id, organization_id, team_id, team_member_id, team_role_id,
                    scope_type, granted_by_principal_id, granted_at, valid_from, status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'TEAM', ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                UUID.randomUUID(),
                fixture.organizationId().value(),
                fixture.team().id().value(),
                secondOwner.id().value(),
                ownerRole.id().value(),
                fixture.owner().id().value(),
                NOW.toOffsetDateTime(),
                NOW.toOffsetDateTime(),
                NOW.toOffsetDateTime(),
                NOW.toOffsetDateTime());
        assertEquals(
                2,
                grants.countEffectiveOwners(
                        fixture.organizationId(),
                        fixture.team().id(),
                        NOW,
                        new TeamMemberId(UUID.randomUUID())));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Outcome> ownerExit =
                executor.submit(() -> runLeave(fixture.owner(), fixture, ready, start));
        Future<Outcome> secondExit =
                executor.submit(() -> runLeave(secondUser, fixture, ready, start));
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        Outcome first = ownerExit.get(30, TimeUnit.SECONDS);
        Outcome second = secondExit.get(30, TimeUnit.SECONDS);
        assertTrue(
                (first == Outcome.COMMITTED && second == Outcome.LAST_OWNER_BLOCKED)
                        || (first == Outcome.LAST_OWNER_BLOCKED && second == Outcome.COMMITTED),
                "exactly one Owner may leave, got " + first + " and " + second);

        String statuses =
                jdbc.queryForObject(
                        "SELECT string_agg(status, ',' ORDER BY user_principal_id)"
                                + " FROM crewscope.team_member WHERE team_id = ?",
                        String.class,
                        fixture.team().id().value());
        assertTrue(statuses != null && statuses.contains("LEFT"));
        assertTrue(statuses != null && statuses.contains("ACTIVE"));
        long remainingOwnerGrants =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.team_member_role grant_row"
                                + " JOIN crewscope.team_role role ON role.id = grant_row.team_role_id"
                                + " WHERE grant_row.team_member_id IN"
                                + " (SELECT id FROM crewscope.team_member WHERE team_id = ?"
                                + " AND status = 'ACTIVE')"
                                + " AND role.role_key = 'TEAM_OWNER' AND grant_row.status = 'ACTIVE'",
                        Long.class,
                        fixture.team().id().value());
        assertEquals(1, remainingOwnerGrants);
    }

    private enum Outcome {
        COMMITTED,
        LAST_OWNER_BLOCKED,
        STALE
    }

    private Outcome runLeave(
            Principal actor, Fixture fixture, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            TeamMember membership =
                    members.findByTeamAndUserPrincipalId(
                                    fixture.organizationId(), fixture.team().id(), actor.id())
                            .orElseThrow();
            service.leaveTeam(
                    context(actor, "leave-" + actor.id() + "-" + UUID.randomUUID()),
                    fixture.team().id(),
                    membership.version());
            return Outcome.COMMITTED;
        } catch (LastOwnerProtectionException blocked) {
            return Outcome.LAST_OWNER_BLOCKED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("owner exit test was interrupted", interrupted);
        }
    }

    @Test
    void concurrentSuspensionsOfOneMemberCommitExactlyOnce() throws Exception {
        Fixture fixture = fixture("Suspend Team");
        Principal developer = addUser(fixture, "Developer");
        TeamMember member = addMember(fixture, developer, BuiltInTeamRole.MEMBER);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Outcome> first =
                executor.submit(
                        () -> runSuspension(fixture, member, ready, start, "suspend-race-1"));
        Future<Outcome> second =
                executor.submit(
                        () -> runSuspension(fixture, member, ready, start, "suspend-race-2"));
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        Outcome firstOutcome = first.get(30, TimeUnit.SECONDS);
        Outcome secondOutcome = second.get(30, TimeUnit.SECONDS);
        assertTrue(
                (firstOutcome == Outcome.COMMITTED && secondOutcome == Outcome.STALE)
                        || (firstOutcome == Outcome.STALE && secondOutcome == Outcome.COMMITTED),
                "exactly one suspension may commit, got " + firstOutcome + " and "
                        + secondOutcome);

        assertEquals(
                "SUSPENDED",
                jdbc.queryForObject(
                        "SELECT status FROM crewscope.team_member WHERE id = ?",
                        String.class,
                        member.id().value()));
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT authorization_version FROM crewscope.team_member WHERE id = ?",
                        Long.class,
                        member.id().value()));
    }

    private Outcome runSuspension(
            Fixture fixture,
            TeamMember member,
            CountDownLatch ready,
            CountDownLatch start,
            String key) {
        try {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            service.suspendMember(context(fixture.owner(), key), fixture.team().id(),
                    member.id(), member.version());
            return Outcome.COMMITTED;
        } catch (OptimisticLockConflictException stale) {
            return Outcome.STALE;
        } catch (LastOwnerProtectionException blocked) {
            return Outcome.LAST_OWNER_BLOCKED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("suspension race was interrupted", interrupted);
        }
    }

    @Test
    void interleavedWritesKeepTheAuthorizationDimensionMonotonic() {
        Fixture fixture = fixture("Interleaved Team");
        Principal developer = addUser(fixture, "Developer");
        TeamMember member = addMember(fixture, developer, BuiltInTeamRole.MEMBER);

        service.grantRole(
                context(fixture.owner(), "interleaved-grant-1"),
                fixture.team().id(),
                member.id(),
                member.version(),
                "TEAM_LEAD");
        assertEquals(2, authorizationVersion(fixture, member));

        TeamMember granted =
                members.findById(fixture.organizationId(), member.id()).orElseThrow();
        service.suspendMember(
                context(fixture.owner(), "interleaved-suspend"),
                fixture.team().id(),
                granted.id(),
                granted.version());
        assertEquals(3, authorizationVersion(fixture, member));
        assertEquals(
                0,
                activeGrants(fixture, member),
                "suspension must revoke every effective grant");

        TeamMember suspended =
                members.findById(fixture.organizationId(), member.id()).orElseThrow();
        service.activateMember(
                context(fixture.owner(), "interleaved-activate"),
                fixture.team().id(),
                suspended.id(),
                suspended.version());
        assertEquals(4, authorizationVersion(fixture, member));
        assertEquals(1, activeGrants(fixture, member), "activation reseats only MEMBER");

        TeamMember activated =
                members.findById(fixture.organizationId(), member.id()).orElseThrow();
        TeamMember regranted =
                service.grantRole(
                        context(fixture.owner(), "interleaved-grant-2"),
                        fixture.team().id(),
                        activated.id(),
                        activated.version(),
                        "TEAM_LEAD")
                        .result()
                        .orElseThrow();
        assertEquals(5, regranted.authorizationVersion());
        assertEquals(5, authorizationVersion(fixture, member));
        assertEquals(4, regranted.version());
    }

    @Test
    void revocationInOneTeamLeavesTheOtherTeamIntact() {
        Fixture first = fixture("First Team");
        // The shared user joins a second Team inside the same Organization under a different
        // membership and a TEAM_LEAD grant.
        Fixture second = fixtureIn(first.organizationId(), "Second Team");
        Principal shared = addUser(first, "Shared Member");
        TeamMember firstMembership = addMember(first, shared, BuiltInTeamRole.MEMBER);
        TeamMember secondMembership = addMember(second, shared, BuiltInTeamRole.MEMBER);
        grants.create(
                MemberRole.grant(
                        MemberRoleId.generate(),
                        secondMembership,
                        roleByKey(second, BuiltInTeamRole.TEAM_LEAD),
                        RoleScope.team(),
                        second.owner().id(),
                        NOW,
                        NOW,
                        Optional.empty()));
        long ownersBefore =
                grants.countEffectiveOwners(
                        second.organizationId(), second.team().id(), NOW, secondMembership.id());

        service.suspendMember(
                context(first.owner(), "cross-team-suspend"),
                first.team().id(),
                firstMembership.id(),
                firstMembership.version());

        assertEquals(
                "SUSPENDED",
                jdbc.queryForObject(
                        "SELECT status FROM crewscope.team_member WHERE id = ?",
                        String.class,
                        firstMembership.id().value()));
        assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                        "SELECT status FROM crewscope.team_member WHERE id = ?",
                        String.class,
                        secondMembership.id().value()));
        assertEquals(0, activeGrants(first, firstMembership));
        assertEquals(2, activeGrants(second, secondMembership));
        assertEquals(
                ownersBefore,
                grants.countEffectiveOwners(
                        second.organizationId(), second.team().id(), NOW, secondMembership.id()));
        assertEquals(
                1,
                grants.countEffectiveOwners(
                        first.organizationId(), first.team().id(), NOW, firstMembership.id()));
        // The browser session projection reuses the same membership facts: the suspended
        // membership drops exactly its own Team while the other Team stays reachable.
        assertEquals(
                List.of(second.team().id()),
                teams.findActiveByMember(first.organizationId(), shared.id()).stream()
                        .map(Team::id)
                        .toList());
    }

    @Test
    void v43BackfillsLegacyRowsToOneAndRejectsNonPositiveValues() {
        Fixture fixture = fixture("Legacy Backfill Team");
        Principal legacyUser = addUser(fixture, "Legacy Member");
        jdbc.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at, version
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'OIDC', ?, 0)
                """,
                UUID.randomUUID(),
                fixture.organizationId().value(),
                fixture.team().id().value(),
                legacyUser.id().value(),
                NOW.toOffsetDateTime());

        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT authorization_version FROM crewscope.team_member"
                                + " WHERE user_principal_id = ? AND team_id = ?",
                        Long.class,
                        legacyUser.id().value(),
                        fixture.team().id().value()));

        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                """
                                INSERT INTO crewscope.team_member (
                                    id, organization_id, team_id, user_principal_id,
                                    status, join_method, joined_at, version, authorization_version
                                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'OIDC', ?, 0, 0)
                                """,
                                UUID.randomUUID(),
                                fixture.organizationId().value(),
                                fixture.team().id().value(),
                                legacyUser.id().value(),
                                NOW.toOffsetDateTime()));
    }

    private long authorizationVersion(Fixture fixture, TeamMember member) {
        return jdbc.queryForObject(
                "SELECT authorization_version FROM crewscope.team_member WHERE id = ?",
                Long.class,
                member.id().value());
    }

    private long activeGrants(Fixture fixture, TeamMember member) {
        return grants.findByMember(fixture.organizationId(), member.id()).stream()
                .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                .filter(grant -> grant.isEffectiveAt(UtcTimestamp.from(NOW.value().plus(Duration.ofMinutes(1)))))
                .filter(grant -> grant.roleScope().equals(RoleScope.team()))
                .count();
    }

    private TeamCommandContext context(Principal actor, String key) {
        return new TeamCommandContext(
                new TeamAccessContext(actor, false),
                IdempotencyKey.from(key),
                UUID.randomUUID(),
                Optional.empty());
    }

    private Fixture fixture(String teamName) {
        OrganizationId organizationId = OrganizationId.generate();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(),
                teamName + " Organization");
        return fixtureIn(organizationId, teamName);
    }

    private Fixture fixtureIn(OrganizationId organizationId, String teamName) {
        Principal owner =
                Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(organizationId),
                        PrincipalType.USER,
                        Optional.empty(),
                        teamName + " Owner",
                        Optional.empty(),
                        PrincipalVisibility.ORGANIZATION,
                        NOW);
        insertPrincipal(organizationId, owner);
        TeamInitialization initialization = TeamInitialization.create(owner, teamName, NOW);
        // One transaction mirrors TeamCreationService: the Team row's deferred owner reference
        // is only checked at commit, after the membership row exists.
        TeamMember ownerMember =
                transactions.required(
                        () -> {
                            teams.create(initialization.team());
                            workspaces.create(initialization.defaultWorkspace());
                            TeamMember created = members.create(initialization.ownerMember());
                            roleRepository.createAll(initialization.builtInRoles());
                            grants.create(initialization.ownerRole());
                            return created;
                        });
        return new Fixture(organizationId, initialization.team(), owner, ownerMember);
    }

    private Principal addUser(Fixture fixture, String displayName) {
        Principal user =
                Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(fixture.organizationId()),
                        PrincipalType.USER,
                        Optional.empty(),
                        displayName,
                        Optional.empty(),
                        PrincipalVisibility.ORGANIZATION,
                        NOW);
        insertPrincipal(fixture.organizationId(), user);
        return user;
    }

    private void insertPrincipal(OrganizationId organizationId, Principal principal) {
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    display_name, visibility, status
                ) VALUES (?, ?, NULL, 'USER', ?, 'ORGANIZATION', 'ACTIVE')
                """,
                principal.id().value(),
                organizationId.value(),
                principal.displayName());
    }

    private TeamMember addMember(Fixture fixture, Principal user, BuiltInTeamRole joinRole) {
        TeamMember member =
                TeamMember.join(
                        TeamMemberId.generate(),
                        new TeamScope(fixture.organizationId(), fixture.team().id()),
                        user,
                        TeamJoinMethod.OIDC,
                        NOW);
        return transactions.required(
                () -> {
                    TeamMember created = members.create(member);
                    grants.create(
                            MemberRole.grant(
                                    MemberRoleId.generate(),
                                    created,
                                    roleByKey(fixture, joinRole),
                                    RoleScope.team(),
                                    fixture.owner().id(),
                                    NOW,
                                    NOW,
                                    Optional.empty()));
                    return created;
                });
    }

    private TeamRole roleByKey(Fixture fixture, BuiltInTeamRole builtIn) {
        return roleRepository.findByTeam(fixture.organizationId(), fixture.team().id()).stream()
                .filter(role -> role.isBuiltIn(builtIn))
                .findFirst()
                .orElseThrow();
    }

    private record Fixture(
            OrganizationId organizationId,
            Team team,
            Principal owner,
            TeamMember ownerMember) {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        TeamPersistenceMapper.class,
        JpaTeamRepositoryAdapter.class,
        JpaTeamMemberRepositoryAdapter.class,
        JpaTeamRoleRepositoryAdapter.class,
        JpaMemberRoleRepositoryAdapter.class,
        JpaPrincipalRepositoryAdapter.class,
        JpaWorkspaceRepositoryAdapter.class,
        JdbcDomainEventStore.class,
        JdbcOutboxRepository.class,
        JdbcCommandReceiptStore.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
