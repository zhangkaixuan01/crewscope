package io.crewscope.server.teambeta;

import static io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest.SHARED_POSTGRES_COMMAND;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * One shared PostgreSQL plus per-class Redis containers for {@code team-beta} integration tests,
 * and one private schema-template database per subclass.
 *
 * <p>The team-beta profile provisions a fixed organization and its bootstrap operator during
 * context refresh, and its assertions count rows inside that organization. Provisioning refuses
 * to converge on data it did not create and row counts would see an earlier class's seed rows,
 * so database isolation has to be per class. It now comes from
 * {@link io.crewscope.infrastructure.testcontainers.SchemaTemplates}: every subclass leases a
 * private physical clone of the migration chain's tip in its static initializer and registers
 * it as the datasource via its own {@code @DynamicPropertySource} — a fresh database is a
 * stronger guarantee than the previous per-class container, at a fraction of the cost. The
 * PostgreSQL container starts once per test JVM (Ryuk reclaims it); the raised connection
 * ceiling covers the ten cached full-application contexts' pools.
 *
 * <p>Redis stays per class on purpose: these tests drive the login defense, and its rate-limit
 * buckets live in Redis on keys that carry no per-context scope — sharing one instance let one
 * class's defense drills (OidcProtocolAttack) push a later class's logins into 429 territory.
 * Each subclass's own {@code @DynamicPropertySource} method (all ten already declare one)
 * keeps the Spring context cache keys distinct, so no class ever connects to another class's
 * lease.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractTeamBetaPostgresRedisContainerIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("crewscope")
                    .withUsername("crewscope")
                    .withPassword("crewscope-test")
                    .withCommand(SHARED_POSTGRES_COMMAND)
                    .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    static {
        POSTGRES.start();
    }

    /**
     * Registers the per-class Redis coordinates before a context is refreshed. The datasource
     * deliberately is not registered here: each subclass points it at its own leased database.
     */
    @DynamicPropertySource
    protected static void registerTeamBetaContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }
}
