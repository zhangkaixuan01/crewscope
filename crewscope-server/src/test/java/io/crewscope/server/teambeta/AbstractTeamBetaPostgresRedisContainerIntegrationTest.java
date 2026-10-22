package io.crewscope.server.teambeta;

import static io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest.SHARED_POSTGRES_COMMAND;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostgreSQL and Redis containers for {@code team-beta} integration tests, plus one
 * private schema-template database per subclass.
 *
 * <p>The team-beta profile provisions a fixed organization and its bootstrap operator during
 * context refresh, and its assertions count rows inside that organization. Provisioning refuses
 * to converge on data it did not create and row counts would see an earlier class's seed rows,
 * so isolation has to be per class. It now comes from {@link io.crewscope.infrastructure.testcontainers.SchemaTemplates}:
 * every subclass leases a private physical clone of the migration chain's tip in its static
 * initializer and registers it as the datasource via its own {@code @DynamicPropertySource} —
 * a fresh database is a stronger guarantee than the previous per-class container pair, at a
 * fraction of the cost. The containers themselves start once per test JVM (Ryuk reclaims them);
 * the raised connection ceiling covers the ten cached full-application contexts' pools.
 *
 * <p>The {@code POSTGRES}/{@code REDIS} field names mirror the shared baseline, so subclasses
 * keep referencing them unchanged. Each subclass's own {@code @DynamicPropertySource} method
 * (all ten already declare one) keeps the Spring context cache keys distinct, so no class ever
 * connects to another class's lease.
 */
public abstract class AbstractTeamBetaPostgresRedisContainerIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("crewscope")
                    .withUsername("crewscope")
                    .withPassword("crewscope-test")
                    .withCommand(SHARED_POSTGRES_COMMAND)
                    .withStartupTimeout(Duration.ofMinutes(2));

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * Registers the shared Redis coordinates before a context is refreshed. The datasource
     * deliberately is not registered here: each subclass points it at its own leased database.
     */
    @DynamicPropertySource
    protected static void registerTeamBetaContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        // Distinct cached contexts share this one Redis, and the single-active-execution guard
        // keeps renewing its lease for as long as its context stays cached (the whole JVM), so a
        // later context would collide on the default scope and fail to start. One scope per
        // context keeps the lease keys disjoint.
        registry.add("crewscope.runtime.redis.ownership-scope",
                () -> "it-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
