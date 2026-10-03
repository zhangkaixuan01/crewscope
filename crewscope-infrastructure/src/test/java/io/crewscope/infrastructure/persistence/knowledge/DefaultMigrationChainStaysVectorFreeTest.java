package io.crewscope.infrastructure.persistence.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plain-postgres guard for the default deployment path (M10-I01a): the default
 * migration chain, migrated to its tip on an image without pgvector, installs no
 * vector extension, no vector-typed column and no second Flyway history. Operators
 * who never opt in keep byte-identical schema state to the pre-I01a baseline.
 */
@Testcontainers(disabledWithoutDocker = true)
class DefaultMigrationChainStaysVectorFreeTest {

    // Plain postgres, not the pgvector image: if any default migration needed the
    // extension binary this migration would fail right here.
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateTheDefaultChainOnPlainPostgres() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .load()
                .migrate();
    }

    @Test
    void installsNoVectorExtensionOnPlainPostgres() {
        Integer extensions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        assertEquals(0, extensions, "opting in is the only path to the extension");
    }

    @Test
    void createsNoVectorTableColumnOrSecondHistory() {
        List<Map<String, Object>> vectorColumns = jdbc.queryForList(
                """
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = 'crewscope' AND data_type LIKE 'vector%'
                """);
        assertTrue(vectorColumns.isEmpty(), "no vector-typed column exists: " + vectorColumns);

        assertFalse(
                Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT to_regclass('crewscope.knowledge_entry_embedding') IS NOT NULL",
                        Boolean.class)),
                "the embedding table only exists on the opt-in chain");
        assertFalse(
                Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT to_regclass('crewscope.flyway_vector_history') IS NOT NULL",
                        Boolean.class)),
                "the second Flyway history only exists on the opt-in chain");
    }
}
