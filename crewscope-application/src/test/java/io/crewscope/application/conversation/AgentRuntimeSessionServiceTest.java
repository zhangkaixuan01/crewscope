package io.crewscope.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AgentRuntimeSessionServiceTest {

    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-08T13:00:00Z");

    @Test
    void retriesReturnOneStableCommittedBinding() {
        AtomicSessionRepository repository = new AtomicSessionRepository();
        CountingTransactionExecutor transactions = new CountingTransactionExecutor();
        AgentRuntimeSessionService service = service(repository, transactions);
        Context context = Context.create("Platform");

        AgentRuntimeSession first = context.ensure(service);
        AgentRuntimeSession retry = context.ensure(service);

        assertEquals(first.id(), retry.id());
        assertEquals(first.agentScopeKey(), retry.agentScopeKey());
        assertEquals(1, repository.persisted.get());
        assertEquals(2, transactions.calls.get());
    }

    @Test
    void concurrentInitializationCommitsOnlyOneSession() throws Exception {
        AtomicSessionRepository repository = new AtomicSessionRepository();
        CountingTransactionExecutor transactions = new CountingTransactionExecutor();
        AgentRuntimeSessionService service = service(repository, transactions);
        Context context = Context.create("Platform");
        int callers = 12;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);

        try {
            List<Future<AgentRuntimeSession>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return context.ensure(service);
                }));
            }
            start.countDown();
            List<AgentRuntimeSession> results = new ArrayList<>();
            for (Future<AgentRuntimeSession> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            assertEquals(1, repository.persisted.get());
            assertEquals(1, repository.values.size());
            assertEquals(
                    1,
                    results.stream()
                            .map(AgentRuntimeSession::id)
                            .distinct()
                            .count());
            assertEquals(callers, transactions.calls.get());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsRepositoryResultForAnotherTeamBinding() {
        Context expected = Context.create("Expected");
        Context foreign = Context.create("Foreign");
        AgentRuntimeSession foreignSession = AgentRuntimeSession.initializePersonal(
                foreign.conversation,
                foreign.team.defaultWorkspace(),
                foreign.team.ownerMember(),
                foreign.owner,
                foreign.team.ownerPersonalAgent(),
                CREATED_AT);
        AgentRuntimeSessionService service = service(
                candidate -> foreignSession, new CountingTransactionExecutor());

        assertThrows(DomainValidationException.class, () -> expected.ensure(service));
    }

    private static AgentRuntimeSessionService service(
            AgentRuntimeSessionRepository repository,
            TransactionExecutor transactionExecutor) {
        TimeProvider timeProvider = TimeProvider.from(Clock.fixed(
                Instant.parse("2026-08-08T13:00:00.123456789Z"), ZoneOffset.UTC));
        return new AgentRuntimeSessionService(repository, transactionExecutor, timeProvider);
    }

    private record Context(
            Principal owner, TeamInitialization team, Conversation conversation) {

        static Context create(String teamName) {
            OrganizationId organizationId = OrganizationId.generate();
            Principal owner = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    teamName + " Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    CREATED_AT);
            TeamInitialization team = TeamInitialization.create(owner, teamName, CREATED_AT);
            Conversation conversation = Conversation.startPersonal(
                    ConversationId.generate(),
                    team.defaultWorkspace(),
                    team.ownerMember(),
                    owner,
                    team.ownerPersonalAgent(),
                    teamName + " Conversation",
                    ConversationVisibility.PRIVATE,
                    CREATED_AT);
            return new Context(owner, team, conversation);
        }

        AgentRuntimeSession ensure(AgentRuntimeSessionService service) {
            PersonalAgentInitialization personalAgent = team.ownerPersonalAgent();
            return service.ensurePersonal(
                    conversation,
                    team.defaultWorkspace(),
                    team.ownerMember(),
                    owner,
                    personalAgent);
        }
    }

    private static final class AtomicSessionRepository
            implements AgentRuntimeSessionRepository {

        private final Map<AgentRuntimeSessionId, AgentRuntimeSession> values =
                new ConcurrentHashMap<>();
        private final AtomicInteger persisted = new AtomicInteger();

        @Override
        public AgentRuntimeSession initializeIfAbsent(AgentRuntimeSession candidate) {
            return values.computeIfAbsent(candidate.id(), ignored -> {
                persisted.incrementAndGet();
                return candidate;
            });
        }
    }

    private static final class CountingTransactionExecutor implements TransactionExecutor {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public <T> T required(Supplier<T> operation) {
            calls.incrementAndGet();
            return operation.get();
        }
    }
}
