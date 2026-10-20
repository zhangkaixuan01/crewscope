package io.crewscope.infrastructure.testcontainers;

import java.time.Duration;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostgreSQL and Redis Testcontainers baseline for infrastructure integration tests.
 *
 * <p>Container endpoints are registered as Spring dynamic properties, so subclasses never depend
 * on the localhost defaults from {@code application.yml}.
 *
 * <p>Singleton container pattern: the pair starts once per test JVM in a static initializer and
 * is never stopped per class (Ryuk reclaims it when the JVM exits). With ~116 subclasses, the
 * per-class start/stop of the previous {@code @Container} fields dominated the module's wall
 * clock; isolation is unaffected because subclasses reset their own data (DROP SCHEMA/TRUNCATE)
 * before use.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPostgresRedisContainerIntegrationTest {

    protected static final String POSTGRES_IMAGE = "postgres:17-alpine";
    protected static final String REDIS_IMAGE = "redis:7.4-alpine";
    protected static final int REDIS_PORT = 6379;

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName("crewscope")
                    .withUsername("crewscope")
                    .withPassword("crewscope-test")
                    // One shared instance serves every cached Spring context of the module run;
                    // the default 100-connection ceiling is exhausted by ~64 contexts x Hikari
                    // pools alone, long before any test connection.
                    .withCommand("postgres", "-c", "max_connections=500")
                    .withStartupTimeout(Duration.ofMinutes(2));

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /** Registers container coordinates before a Spring test ApplicationContext is refreshed. */
    @DynamicPropertySource
    protected static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        // Distinct cached contexts share this one Redis, and the single-active-execution guard
        // keeps renewing its lease for as long as its context stays cached (the whole JVM), so a
        // later context would collide on the default scope and fail to start. One scope per
        // context keeps the lease keys disjoint; the production mutual-exclusion semantics stay
        // covered by RedisAgentStateM2I05IntegrationTest, which drives the guard directly.
        registry.add("crewscope.runtime.redis.ownership-scope",
                () -> "it-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
