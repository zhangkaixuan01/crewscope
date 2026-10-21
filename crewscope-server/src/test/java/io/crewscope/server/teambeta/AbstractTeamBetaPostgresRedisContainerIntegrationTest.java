package io.crewscope.server.teambeta;

import static io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest.NON_DURABLE_POSTGRES_COMMAND;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Per-class PostgreSQL and Redis containers for {@code team-beta} integration tests.
 *
 * <p>The team-beta profile provisions a fixed organization and its bootstrap operator during
 * context refresh, and its assertions count rows inside that organization. On the shared
 * singleton containers (see {@link AbstractPostgresRedisContainerIntegrationTest}) neither
 * survives contact with another team-beta context's leftovers — provisioning refuses to
 * converge on data it did not create, and row counts see the earlier class's seed rows — so
 * these ten classes keep the classic per-class start/stop and pay its few seconds each.
 * The {@code POSTGRES}/{@code REDIS} field names mirror the shared baseline, so subclasses
 * keep referencing them unchanged.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractTeamBetaPostgresRedisContainerIntegrationTest {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("crewscope")
                    .withUsername("crewscope")
                    .withPassword("crewscope-test")
                    .withCommand(NON_DURABLE_POSTGRES_COMMAND)
                    .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .withStartupTimeout(Duration.ofMinutes(2));

    /** Registers this class's own container coordinates before its context is refreshed. */
    @DynamicPropertySource
    protected static void registerTeamBetaContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }
}
