package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.runtime.ExecutionRuntimeRepository;
import io.crewscope.application.runtime.RuntimeWorkerRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerStatus;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.persistence.taskruntime.JpaTaskRuntimeRepositoryAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.TaskRuntimePersistenceMapper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL evidence for stable Runtime/Worker identity, reconciliation and Heartbeat facts. */
@SpringBootTest(
        classes = RuntimeRegistryM3I01IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class RuntimeRegistryM3I01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp START = UtcTimestamp.parse("2026-08-14T12:00:00Z");

    @Autowired private ExecutionRuntimeRepository runtimeRepository;
    @Autowired private RuntimeWorkerRepository workerRepository;
    @Autowired private TransactionExecutor transactionExecutor;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final AtomicReference<UtcTimestamp> now = new AtomicReference<>(START);
    private OrganizationId organizationId;
    private Principal actor;

    @BeforeEach
    void seedRuntimeTenant() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        organizationId = OrganizationId.generate();
        actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.SERVICE,
                Optional.empty(),
                "Runtime Registry",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                START);
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Runtime Org', 'ACTIVE')",
                organizationId.value());
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'SERVICE', 'Runtime Registry', 'ORGANIZATION', 'ACTIVE')
                """,
                actor.id().value(),
                organizationId.value());
    }

    @Test
    void registersOnceReusesIdentityAcrossRestartAndSupportsTwoWorkers() {
        RuntimeRegistryCoordinator first = coordinator("worker-a", "2.0.0", fullCapabilities());
        RuntimeWorkerIdentity initial = first.register();

        now.set(UtcTimestamp.parse("2026-08-14T12:00:05Z"));
        RuntimeWorkerIdentity restarted = coordinator(
                "worker-a", "2.0.0", fullCapabilities()).register();
        RuntimeWorkerIdentity second = coordinator(
                "worker-b", "2.0.0", fullCapabilities()).register();

        assertEquals(initial, restarted);
        assertEquals(initial.runtimeId(), second.runtimeId());
        assertNotEquals(initial.workerId(), second.workerId());
        assertEquals(1, count("execution_runtime"));
        assertEquals(2, count("runtime_worker"));
    }

    @Test
    void publishesCapabilityChangesDerivesExpiryAndKeepsDrainDuringHeartbeat() {
        RuntimeRegistryCoordinator initial = coordinator(
                "worker-a", "2.0.0", fullCapabilities());
        RuntimeWorkerIdentity identity = initial.register();

        now.set(UtcTimestamp.parse("2026-08-14T12:00:05Z"));
        RuntimeCapabilities reduced = RuntimeCapabilities.of(RuntimeCapability.CONVERSATION);
        RuntimeRegistryCoordinator upgraded = coordinator("worker-a", "2.0.1", reduced);
        assertEquals(identity, upgraded.register());
        assertEquals("2.0.1", runtimeRepository.findByKey(
                organizationId, new RuntimeEnvironment("development"), "agentscope-java")
                .orElseThrow().implementationVersion());
        assertEquals(reduced, workerRepository.findByStableKey(
                organizationId,
                new RuntimeEnvironment("development"),
                identity.runtimeId(),
                "worker-a").orElseThrow().capabilities());

        now.set(UtcTimestamp.parse("2026-08-14T12:00:36Z"));
        RuntimeWorkerHealth stale = upgraded.health();
        assertFalse(stale.heartbeatFresh());
        assertEquals(RuntimeWorkerStatus.ACTIVE, stale.status());

        now.set(UtcTimestamp.parse("2026-08-14T12:00:37Z"));
        upgraded.beginDrain();
        now.set(UtcTimestamp.parse("2026-08-14T12:00:38Z"));
        upgraded.heartbeat();
        RuntimeWorkerHealth draining = upgraded.health();
        assertTrue(draining.heartbeatFresh());
        assertFalse(draining.claimable());
        assertEquals(RuntimeWorkerStatus.DRAINING, draining.status());
        assertTrue(draining.heartbeatSequence() >= 3);
    }

    @Test
    void concurrentFirstRegistrationConvergesOnOneStableIdentity() throws Exception {
        RuntimeRegistryCoordinator left = coordinator(
                "worker-race", "2.0.0", fullCapabilities());
        RuntimeRegistryCoordinator right = coordinator(
                "worker-race", "2.0.0", fullCapabilities());
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeWorkerIdentity> first = executor.submit(() -> {
                start.await();
                return left.register();
            });
            Future<RuntimeWorkerIdentity> second = executor.submit(() -> {
                start.await();
                return right.register();
            });
            start.countDown();

            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("execution_runtime"));
        assertEquals(1, count("runtime_worker"));
    }

    @Test
    void heartbeatPublishesAuthoritativeJvmCapacity() {
        AtomicInteger activeExecutions = new AtomicInteger(1);
        RuntimeRegistryCoordinator coordinator = coordinator(
                "worker-capacity",
                "2.0.0",
                fullCapabilities(),
                activeExecutions::get);
        RuntimeWorkerIdentity identity = coordinator.register();
        assertEquals(1, worker(identity).capacity().activeExecutions());

        now.set(UtcTimestamp.parse("2026-08-14T12:00:05Z"));
        activeExecutions.set(3);
        coordinator.heartbeat();

        assertEquals(3, worker(identity).capacity().activeExecutions());
        assertEquals(4, worker(identity).capacity().maxConcurrentExecutions());
    }

    private RuntimeRegistryCoordinator coordinator(
            String stableKey, String implementationVersion, RuntimeCapabilities capabilities) {
        return coordinator(stableKey, implementationVersion, capabilities, () -> 0);
    }

    private RuntimeRegistryCoordinator coordinator(
            String stableKey,
            String implementationVersion,
            RuntimeCapabilities capabilities,
            RuntimeWorkerLoadProvider loadProvider) {
        TimeProvider timeProvider = now::get;
        RuntimeWorkerRegistrationSpec spec = new RuntimeWorkerRegistrationSpec(
                organizationId,
                new RuntimeEnvironment("development"),
                "agentscope-java",
                "AgentScope Java",
                implementationVersion,
                capabilities,
                stableKey,
                RuntimeProfile.WORKER,
                capabilities,
                4,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                actor);
        return new RuntimeRegistryCoordinator(
                runtimeRepository,
                workerRepository,
                transactionExecutor,
                timeProvider,
                spec,
                loadProvider);
    }

    private RuntimeWorker worker(RuntimeWorkerIdentity identity) {
        return workerRepository.findByStableKey(
                organizationId,
                new RuntimeEnvironment("development"),
                identity.runtimeId(),
                identity.stableKey()).orElseThrow();
    }

    private RuntimeCapabilities fullCapabilities() {
        return RuntimeCapabilities.of(
                Set.of(RuntimeCapability.CONVERSATION, RuntimeCapability.PLAN),
                Set.of("java"),
                Set.of("maven"));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope." + table, Integer.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "io.crewscope.infrastructure.persistence")
    @Import({
        TaskRuntimePersistenceMapper.class,
        JpaTaskRuntimeRepositoryAdapter.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
