package io.crewscope.infrastructure.testcontainers;

import java.time.Duration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared pgvector Testcontainers baseline for the optional vector-store chain
 * (M10-I01a). The image is the one measured in S01b ({@code pgvector/pgvector:pg17}):
 * upstream postgres with the vector extension preinstalled, so the vector migration
 * chain can {@code CREATE EXTENSION} as the container's superuser. Tests stay pure
 * JUnit — no Spring context — and drive Flyway explicitly like
 * {@code JdbcCommandResultIntegrationTest}.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractPgVectorContainerIntegrationTest {

    protected static final String PGVECTOR_IMAGE = "pgvector/pgvector:pg17";

    @Container
    protected static final PostgreSQLContainer<?> PGVECTOR =
            new PostgreSQLContainer<>(DockerImageName.parse(PGVECTOR_IMAGE))
                    .withDatabaseName("crewscope")
                    .withUsername("crewscope")
                    .withPassword("crewscope-test")
                    .withStartupTimeout(Duration.ofMinutes(2));
}
