package io.crewscope.infrastructure.testcontainers;

import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostgreSQL and Redis Testcontainers baseline for infrastructure integration tests.
 *
 * <p>Container endpoints are registered as Spring dynamic properties, so subclasses never depend
 * on the localhost defaults from {@code application.yml}.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgresRedisContainerIntegrationTest {

    protected static final String POSTGRES_IMAGE = "postgres:17-alpine";
    protected static final String REDIS_IMAGE = "redis:7.4-alpine";
    protected static final int REDIS_PORT = 6379;

    @Container
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName("crewscope")
                    .withUsername("crewscope")
                    .withPassword("crewscope-test")
                    .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    /** Registers container coordinates before a Spring test ApplicationContext is refreshed. */
    @DynamicPropertySource
    protected static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }
}
