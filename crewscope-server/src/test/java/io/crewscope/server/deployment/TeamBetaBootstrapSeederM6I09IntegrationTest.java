package io.crewscope.server.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Verifies clean-schema bootstrap, idempotent restart and conflicting-fact failure closure. */
@Testcontainers(disabledWithoutDocker = true)
class TeamBetaBootstrapSeederM6I09IntegrationTest {

    private static final String ORGANIZATION_ID = "0198a475-0831-7000-8000-000000000001";
    private static final String PRINCIPAL_ID = "0198a475-0831-7000-8000-000000000002";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("crewscope")
                    .withUsername("crewscope")
                    .withPassword("crewscope-test")
                    .withStartupTimeout(Duration.ofMinutes(2));

    private PGSimpleDataSource dataSource;

    @BeforeEach
    void migrateCleanDatabase() throws SQLException {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .createSchemas(true)
                .load()
                .migrate();
    }

    @Test
    void seedsIdempotentlyAndRejectsConflictingExistingFacts() throws SQLException {
        TeamBetaBootstrapSeeder seeder = new TeamBetaBootstrapSeeder(
                ORGANIZATION_ID, "CrewScope Team Beta", PRINCIPAL_ID);

        seeder.seed(dataSource);
        seeder.seed(dataSource);
        assertEquals(1, count("crewscope.organization", ORGANIZATION_ID));
        assertEquals(1, count("crewscope.principal", PRINCIPAL_ID));

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE crewscope.organization
                    SET name = 'Conflicting Organization'
                    WHERE id = '0198a475-0831-7000-8000-000000000001'
                    """);
        }
        assertThrows(IllegalStateException.class, () -> seeder.seed(dataSource));
        assertEquals(1, count("crewscope.principal", PRINCIPAL_ID));
    }

    private int count(String table, String id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE id = '" + id + "'")) {
            result.next();
            return result.getInt(1);
        }
    }
}
