package io.crewscope.infrastructure.persistence.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.AccountOrganizationBindingRepository;
import io.crewscope.application.identity.LocalAccountRegistrationCommand;
import io.crewscope.application.identity.LocalAccountRegistrationContext;
import io.crewscope.application.identity.LocalAccountRegistrationException;
import io.crewscope.application.identity.LocalAccountRegistrationFailure;
import io.crewscope.application.identity.LocalAccountRegistrationResult;
import io.crewscope.application.identity.LocalCredentialStore;
import io.crewscope.application.identity.LocalPasswordAuthentication;
import io.crewscope.application.identity.LocalPasswordVerification;
import io.crewscope.application.identity.LoginIdentityRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.team.CreateTeamCommand;
import io.crewscope.application.team.DefaultPersonalAgentRepository;
import io.crewscope.application.team.DefaultPersonalAgentService;
import io.crewscope.application.team.InvitationToken;
import io.crewscope.application.team.InvitationTokenDigester;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamCreationService;
import io.crewscope.application.team.TeamInvitationAcceptanceService;
import io.crewscope.application.team.TeamInvitationIssueResult;
import io.crewscope.application.team.TeamInvitationIssueService;
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
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.InvitationTokenDigest;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.infrastructure.persistence.JpaPersistenceConfiguration;
import io.crewscope.infrastructure.persistence.command.JdbcCommandReceiptStore;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.persistence.team.JpaAgentProfileRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaMemberRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaPrincipalRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamMemberRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaWorkspaceRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JdbcTeamInvitationRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.TeamInvitationPersistenceMapper;
import io.crewscope.infrastructure.persistence.team.TeamPersistenceMapper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL proof for M7-Q02 identity, invitation and commit-window convergence. */
@SpringBootTest(
        classes = M7IdentityInvitationTransactionConvergenceM7Q02IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class M7IdentityInvitationTransactionConvergenceM7Q02IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-29T08:00:00Z"));
    private static final LocalPasswordHash FIXED_HASH =
            new LocalPasswordHash("{argon2id}$m7-q02-fixed-password-hash-body");
    private static final InvitationToken TOKEN = token(42);
    private static final InvitationTokenDigest TOKEN_DIGEST = digest(42);

    @Autowired private UserAccountRepository accounts;
    @Autowired private LoginIdentityRepository identities;
    @Autowired private LocalCredentialStore credentials;
    @Autowired private PrincipalRepository principals;
    @Autowired private AccountOrganizationBindingRepository bindings;
    @Autowired private TeamInvitationRepository invitations;
    @Autowired private TeamRepository teams;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private TeamMemberRepository members;
    @Autowired private TeamRoleRepository roles;
    @Autowired private MemberRoleRepository memberRoles;
    @Autowired private DefaultPersonalAgentRepository personalAgents;
    @Autowired private DomainEventStore events;
    @Autowired private OutboxRepository outbox;
    @Autowired private CommandReceiptStore receipts;
    @Autowired private TransactionExecutor transactions;
    @Autowired private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("TRUNCATE TABLE crewscope.user_account, crewscope.organization CASCADE");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void concurrentCompleteRegistrationCommitsOneAuditedIdentityChain() throws Exception {
        OrganizationId organizationId = organization("Concurrent Registration");
        var service = service(identities, bindings, events, receipts, Optional.empty());
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> attempts = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int sequence = index;
            attempts.add(executor.submit(() -> {
                ready.countDown();
                await(start);
                return registerOutcome(
                        service,
                        context(organizationId, "concurrent-" + sequence),
                        command("Alice", "alice-" + sequence + "@example.test", Optional.empty()));
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        List<Object> outcomes = attempts.stream().map(M7IdentityInvitationTransactionConvergenceM7Q02IntegrationTest::get).toList();
        assertEquals(1, outcomes.stream().filter(LocalAccountRegistrationResult.class::isInstance).count());
        assertEquals(7, outcomes.stream().filter(LocalAccountRegistrationException.class::isInstance).count());
        outcomes.stream()
                .filter(LocalAccountRegistrationException.class::isInstance)
                .map(LocalAccountRegistrationException.class::cast)
                .forEach(failure -> assertEquals(
                        LocalAccountRegistrationFailure.REGISTRATION_CONFLICT,
                        failure.failure()));

        assertIdentityChainCounts(1, 1, 1, 1, 1, 1, 1);
    }

    @Test
    void concurrentInvitationRegistrationCommitsOneMembershipAndCompleteAuditChain()
            throws Exception {
        OrganizationId organizationId = organization("Concurrent Invitation");
        TeamFixture fixture = teamFixture(organizationId);
        TeamInvitationIssueResult issued = new TeamInvitationIssueService(
                        invitations,
                        () -> TOKEN,
                        ignored -> TOKEN_DIGEST,
                        transactions,
                        () -> NOW)
                .issue(
                        fixture.initialization().team(),
                        fixture.inviter(),
                        Optional.empty(),
                        BuiltInTeamRole.MEMBER,
                        Duration.ofDays(7));
        assertEquals(TOKEN.reveal(), issued.revealToken());

        var service = service(
                identities,
                bindings,
                events,
                receipts,
                Optional.of(ignored -> TOKEN_DIGEST));
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> attempts = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int sequence = index;
            attempts.add(executor.submit(() -> {
                ready.countDown();
                await(start);
                return registerOutcome(
                        service,
                        new LocalAccountRegistrationContext(
                                organizationId,
                                RegistrationMode.INVITE_ONLY,
                                IdempotencyKey.from("invite-concurrent-" + sequence),
                                UUID.randomUUID(),
                                Optional.empty()),
                        command(
                                "invited-" + sequence,
                                "invited-" + sequence + "@example.test",
                                Optional.of(TOKEN)));
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        List<Object> outcomes = attempts.stream().map(M7IdentityInvitationTransactionConvergenceM7Q02IntegrationTest::get).toList();
        List<LocalAccountRegistrationResult> winners = outcomes.stream()
                .filter(LocalAccountRegistrationResult.class::isInstance)
                .map(LocalAccountRegistrationResult.class::cast)
                .toList();
        assertEquals(1, winners.size(), outcomes.toString());
        LocalAccountRegistrationResult winner = winners.get(0);
        assertEquals(7, outcomes.stream().filter(LocalAccountRegistrationException.class::isInstance).count());
        assertTrue(winner.acceptedInvitation().isPresent());
        assertTrue(winner.membership().isPresent());
        assertFalse(winner.replayed());

        assertIdentityChainCounts(1, 1, 1, 1, 1, 2, 2);
        assertEquals(2, count("team_member"));
        assertEquals(2, count("team_member_role"));
        assertEquals(2, count("agent_profile"));
        assertEquals(2, countWhere("agent_profile", "default_profile = true"));
        assertEquals(1, countWhere("team_invitation", "status = 'ACCEPTED'"));
        assertEquals(1, countWhere(
                "team_invitation",
                "accepted_by_account_id IS NOT NULL AND accepted_member_id IS NOT NULL"));
    }

    @Test
    void everyInjectedCommitWindowFailureRollsBackTheWholeIdentityChain() {
        List<FailureWindow> windows = List.of(
                new FailureWindow("identity", this::failAfterIdentityCreate),
                new FailureWindow("binding", this::failAfterBindingCreate),
                new FailureWindow("event", this::failAfterEventAppend),
                new FailureWindow("receipt", this::failAfterReceiptComplete));

        for (FailureWindow window : windows) {
            jdbc.execute("TRUNCATE TABLE crewscope.user_account, crewscope.organization CASCADE");
            OrganizationId organizationId = organization("Rollback " + window.name());
            ServicePorts ports = new ServicePorts(identities, bindings, events, receipts);
            window.injector().accept(ports);
            var service = service(
                    ports.identities(),
                    ports.bindings(),
                    ports.events(),
                    ports.receipts(),
                    Optional.empty());

            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> service.register(
                                    context(organizationId, "rollback-" + window.name()),
                                    command(
                                            "rollback-" + window.name(),
                                            window.name() + "@example.test",
                                            Optional.empty()))
                            .toCompletableFuture()
                            .join());
            assertInstanceOf(InjectedProcessFailure.class, rootCause(failure));
            assertIdentityChainCounts(0, 0, 0, 0, 0, 0, 0);
        }
    }

    @Test
    void committedRegistrationReplaysAfterAProcessLosesTheResponseWithoutDuplicatingFacts() {
        OrganizationId organizationId = organization("Lost Registration Response");
        LocalAccountRegistrationContext context = context(organizationId, "lost-response");
        LocalAccountRegistrationCommand command =
                command("recoverable", "recoverable@example.test", Optional.empty());

        LocalAccountRegistrationResult committed = service(
                        identities, bindings, events, receipts, Optional.empty())
                .register(context, command)
                .toCompletableFuture()
                .join();
        LocalAccountRegistrationResult recovered = service(
                        identities, bindings, events, receipts, Optional.empty())
                .register(context, command)
                .toCompletableFuture()
                .join();

        assertFalse(committed.replayed());
        assertTrue(recovered.replayed());
        assertEquals(committed.account().id(), recovered.account().id());
        assertEquals(committed.binding().id(), recovered.binding().id());
        assertEquals(committed.receipt(), recovered.receipt());
        assertIdentityChainCounts(1, 1, 1, 1, 1, 1, 1);
    }

    private io.crewscope.application.identity.LocalAccountRegistrationService service(
            LoginIdentityRepository identityPort,
            AccountOrganizationBindingRepository bindingPort,
            DomainEventStore eventPort,
            CommandReceiptStore receiptPort,
            Optional<InvitationTokenDigester> digester) {
        return new io.crewscope.application.identity.LocalAccountRegistrationService(
                accounts,
                identityPort,
                credentials,
                principals,
                bindingPort,
                invitations,
                teams,
                members,
                roles,
                memberRoles,
                workspaces,
                new DefaultPersonalAgentService(personalAgents, transactions, () -> NOW),
                new TeamInvitationAcceptanceService(),
                digester,
                passwordAuthentication(),
                eventPort,
                outbox,
                receiptPort,
                transactions,
                () -> NOW,
                Runnable::run);
    }

    private LocalPasswordAuthentication passwordAuthentication() {
        return new LocalPasswordAuthentication() {
            @Override
            public java.util.concurrent.CompletionStage<LocalPasswordHash> encodeForStorage(
                    String rawPassword) {
                return CompletableFuture.completedFuture(FIXED_HASH);
            }

            @Override
            public java.util.concurrent.CompletionStage<LocalPasswordVerification> verify(
                    String rawPassword,
                    Optional<io.crewscope.application.identity.LocalCredentialAuthenticationMaterial>
                            credential,
                    boolean accountCanAuthenticate) {
                return CompletableFuture.completedFuture(LocalPasswordVerification.authenticated(
                        LocalPasswordVerification.Upgrade.NOT_REQUIRED));
            }
        };
    }

    private TeamFixture teamFixture(OrganizationId organizationId) {
        Principal inviter = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Invitation Owner",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        transactions.required(() -> principals.createLocalUser(inviter));
        TeamCreationService creation = new TeamCreationService(
                teams,
                workspaces,
                members,
                roles,
                memberRoles,
                personalAgents,
                (team, workspace, actor) -> {},
                transactions,
                () -> NOW,
                (actor, occurredAt) -> {},
                (catalogOrganizationId, actor, occurredAt) -> {},
                (team, workspace, ownerMember, ownerUser) -> {});
        TeamInitialization initialization =
                creation.create(inviter, new CreateTeamCommand("Invitation Team"));
        return new TeamFixture(inviter, initialization);
    }

    private OrganizationId organization(String name) {
        OrganizationId organizationId = OrganizationId.generate();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(),
                name);
        return organizationId;
    }

    private LocalAccountRegistrationContext context(
            OrganizationId organizationId, String idempotencyKey) {
        return new LocalAccountRegistrationContext(
                organizationId,
                RegistrationMode.OPEN,
                IdempotencyKey.from(idempotencyKey),
                UUID.randomUUID(),
                Optional.empty());
    }

    private LocalAccountRegistrationCommand command(
            String username, String email, Optional<InvitationToken> invitationToken) {
        return new LocalAccountRegistrationCommand(
                username,
                email,
                "M7 Q02 User",
                "Correct-Horse-Battery-Staple-47",
                invitationToken);
    }

    private void failAfterIdentityCreate(ServicePorts ports) {
        LoginIdentityRepository failing = mock(LoginIdentityRepository.class, delegatesTo(identities));
        doAnswer(invocation -> {
                    identities.create(invocation.getArgument(0));
                    throw new InjectedProcessFailure("identity");
                })
                .when(failing)
                .create(any());
        ports.identities(failing);
    }

    private void failAfterBindingCreate(ServicePorts ports) {
        AccountOrganizationBindingRepository failing =
                mock(AccountOrganizationBindingRepository.class, delegatesTo(bindings));
        doAnswer(invocation -> {
                    bindings.create(invocation.getArgument(0));
                    throw new InjectedProcessFailure("binding");
                })
                .when(failing)
                .create(any());
        ports.bindings(failing);
    }

    private void failAfterEventAppend(ServicePorts ports) {
        DomainEventStore failing = mock(DomainEventStore.class, delegatesTo(events));
        doAnswer(invocation -> {
                    events.append(invocation.getArgument(0));
                    throw new InjectedProcessFailure("event");
                })
                .when(failing)
                .append(any());
        ports.events(failing);
    }

    private void failAfterReceiptComplete(ServicePorts ports) {
        CommandReceiptStore failing = mock(CommandReceiptStore.class, delegatesTo(receipts));
        doAnswer(invocation -> {
                    receipts.complete(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3));
                    throw new InjectedProcessFailure("receipt");
                })
                .when(failing)
                .complete(any(), any(), any(CommandReceipt.class), any());
        ports.receipts(failing);
    }

    private void assertIdentityChainCounts(
            int accounts,
            int loginIdentities,
            int localCredentials,
            int bindings,
            int commandReceipts,
            int domainEvents,
            int outboxEvents) {
        assertEquals(accounts, count("user_account"));
        assertEquals(loginIdentities, count("login_identity"));
        assertEquals(localCredentials, count("local_credential"));
        assertEquals(bindings, count("account_organization_binding"));
        assertEquals(commandReceipts, count("command_receipt"));
        assertEquals(domainEvents, count("domain_event"));
        assertEquals(outboxEvents, count("outbox_event"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM crewscope." + table, Integer.class);
    }

    private int countWhere(String table, String predicate) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope." + table + " WHERE " + predicate,
                Integer.class);
    }

    private static Object registerOutcome(
            io.crewscope.application.identity.LocalAccountRegistrationService service,
            LocalAccountRegistrationContext context,
            LocalAccountRegistrationCommand command) {
        try {
            return service.register(context, command).toCompletableFuture().join();
        } catch (CompletionException failure) {
            return rootCause(failure);
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Object get(Future<Object> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for M7-Q02 concurrency coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static InvitationToken token(int fill) {
        byte[] value = new byte[InvitationToken.ENTROPY_BYTES];
        java.util.Arrays.fill(value, (byte) fill);
        return new InvitationToken(Base64.getUrlEncoder().withoutPadding().encodeToString(value));
    }

    private static InvitationTokenDigest digest(int fill) {
        byte[] value = new byte[InvitationTokenDigest.BYTE_LENGTH];
        java.util.Arrays.fill(value, (byte) fill);
        return InvitationTokenDigest.fromBytes(value);
    }

    private record TeamFixture(Principal inviter, TeamInitialization initialization) {}

    private record FailureWindow(String name, Consumer<ServicePorts> injector) {}

    private static final class ServicePorts {

        private LoginIdentityRepository identities;
        private AccountOrganizationBindingRepository bindings;
        private DomainEventStore events;
        private CommandReceiptStore receipts;

        private ServicePorts(
                LoginIdentityRepository identities,
                AccountOrganizationBindingRepository bindings,
                DomainEventStore events,
                CommandReceiptStore receipts) {
            this.identities = identities;
            this.bindings = bindings;
            this.events = events;
            this.receipts = receipts;
        }

        LoginIdentityRepository identities() {
            return identities;
        }

        void identities(LoginIdentityRepository value) {
            identities = value;
        }

        AccountOrganizationBindingRepository bindings() {
            return bindings;
        }

        void bindings(AccountOrganizationBindingRepository value) {
            bindings = value;
        }

        DomainEventStore events() {
            return events;
        }

        void events(DomainEventStore value) {
            events = value;
        }

        CommandReceiptStore receipts() {
            return receipts;
        }

        void receipts(CommandReceiptStore value) {
            receipts = value;
        }
    }

    private static final class InjectedProcessFailure extends RuntimeException {

        private InjectedProcessFailure(String window) {
            super("Injected M7-Q02 process failure at " + window);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = JpaPersistenceConfiguration.class)
    @Import({
        IdentityPersistenceMapper.class,
        JdbcUserAccountRepositoryAdapter.class,
        JdbcLoginIdentityRepositoryAdapter.class,
        JdbcLocalCredentialStoreAdapter.class,
        JdbcAccountOrganizationBindingRepositoryAdapter.class,
        TeamPersistenceMapper.class,
        JpaPrincipalRepositoryAdapter.class,
        JpaTeamRepositoryAdapter.class,
        JpaWorkspaceRepositoryAdapter.class,
        JpaTeamMemberRepositoryAdapter.class,
        JpaTeamRoleRepositoryAdapter.class,
        JpaMemberRoleRepositoryAdapter.class,
        JpaAgentProfileRepositoryAdapter.class,
        TeamInvitationPersistenceMapper.class,
        JdbcTeamInvitationRepositoryAdapter.class,
        JdbcDomainEventStore.class,
        JdbcOutboxRepository.class,
        JdbcCommandReceiptStore.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
