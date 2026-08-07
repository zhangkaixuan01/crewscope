package io.crewscope.application.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.transaction.TransactionExecutor;
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
import io.crewscope.domain.team.TeamMemberId;
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

class DefaultPersonalAgentServiceTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-07T21:00:00Z");

    @Test
    void repeatedInitializationReturnsOneStableAgentPair() {
        AtomicDefaultRepository repository = new AtomicDefaultRepository();
        CountingTransactionExecutor transactions = new CountingTransactionExecutor();
        DefaultPersonalAgentService service = service(repository, transactions);
        Principal owner = activeUser("Owner");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);

        PersonalAgentInitialization first = service.ensureDefault(
                team.ownerMember(), team.defaultWorkspace(), owner);
        PersonalAgentInitialization retry = service.ensureDefault(
                team.ownerMember(), team.defaultWorkspace(), owner);

        assertEquals(first.agentPrincipal().id(), retry.agentPrincipal().id());
        assertEquals(first.agentProfile().id(), retry.agentProfile().id());
        assertEquals(1, repository.persisted.get());
        assertEquals(2, transactions.calls.get());
    }

    @Test
    void concurrentInitializationCommitsOnlyOneDefaultAgent() throws Exception {
        AtomicDefaultRepository repository = new AtomicDefaultRepository();
        CountingTransactionExecutor transactions = new CountingTransactionExecutor();
        DefaultPersonalAgentService service = service(repository, transactions);
        Principal owner = activeUser("Owner");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);
        int callers = 12;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);

        try {
            List<Future<PersonalAgentInitialization>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.ensureDefault(
                            team.ownerMember(), team.defaultWorkspace(), owner);
                }));
            }
            start.countDown();
            List<PersonalAgentInitialization> results = new ArrayList<>();
            for (Future<PersonalAgentInitialization> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            assertEquals(1, repository.persisted.get());
            assertEquals(1, repository.values.size());
            assertEquals(
                    1,
                    results.stream()
                            .map(result -> result.agentPrincipal().id())
                            .distinct()
                            .count());
            assertEquals(callers, transactions.calls.get());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsRepositoryResultForAnotherMember() {
        Principal owner = activeUser("Owner");
        Principal otherOwner = activeUser("Other");
        TeamInitialization team = TeamInitialization.create(owner, "Platform", CREATED_AT);
        TeamInitialization otherTeam =
                TeamInitialization.create(otherOwner, "Other", CREATED_AT);
        DefaultPersonalAgentRepository wrongRepository = candidate -> otherTeam.ownerPersonalAgent();
        DefaultPersonalAgentService service = service(
                wrongRepository, new CountingTransactionExecutor());

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> service.ensureDefault(
                        team.ownerMember(), team.defaultWorkspace(), owner));

        assertEquals(
                "personalAgentInitialization.agentProfile",
                failure.error().details().get("field"));
    }

    private static DefaultPersonalAgentService service(
            DefaultPersonalAgentRepository repository,
            TransactionExecutor transactionExecutor) {
        TimeProvider timeProvider = TimeProvider.from(Clock.fixed(
                Instant.parse("2026-08-07T21:00:00.123456789Z"), ZoneOffset.UTC));
        return new DefaultPersonalAgentService(repository, transactionExecutor, timeProvider);
    }

    private static Principal activeUser(String displayName) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                displayName,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }

    private static final class AtomicDefaultRepository
            implements DefaultPersonalAgentRepository {

        private final Map<TeamMemberId, PersonalAgentInitialization> values =
                new ConcurrentHashMap<>();
        private final AtomicInteger persisted = new AtomicInteger();

        @Override
        public PersonalAgentInitialization initializeIfAbsent(
                PersonalAgentInitialization candidate) {
            return values.computeIfAbsent(
                    candidate.agentProfile().ownerMemberId().orElseThrow(),
                    ignored -> {
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
