package io.crewscope.infrastructure.testcontainers;

import java.time.Duration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared pgvector Testcontainers baseline for the optional vector-store chain
 * (M10-I01a). The image is the one measured in S01b ({@code pgvector/pgvector:pg17}):
 * upstream postgres with the vector extension preinstalled, so the vector migration
 * chain can {@code CREATE EXTENSION} as the container's superuser. Tests stay pure
 * JUnit — no Spring context — and drive Flyway explicitly like
 * {@code JdbcCommandResultIntegrationTest}.
 *
 * <p>Singleton container pattern (started once per test JVM in a static initializer, stopped by
 * Ryuk on JVM exit) — subclasses reset their own schema before use, so sharing one instance
 * across classes changes nothing but the wall clock.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPgVectorContainerIntegrationTest {

    protected static final String PGVECTOR_IMAGE = "pgvector/pgvector:pg17";

    protected static final PostgreSQLContainer<?> PGVECTOR =
            new PostgreSQLContainer<>(DockerImageName.parse(PGVECTOR_IMAGE))
                    .withDatabaseName("crewscope")
                    .withUsername("crewscope")
                    .withPassword("crewscope-test")
                    // Singleton instance shared by every subclass; raised from the default 100
                    // so concurrent cached pools cannot exhaust the ceiling.
                    .withCommand("postgres", "-c", "max_connections=500")
                    .withStartupTimeout(Duration.ofMinutes(2));

    static {
        PGVECTOR.start();
    }
}
