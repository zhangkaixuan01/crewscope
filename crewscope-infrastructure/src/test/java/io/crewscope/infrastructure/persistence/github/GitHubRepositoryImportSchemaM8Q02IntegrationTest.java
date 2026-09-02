package io.crewscope.infrastructure.persistence.github;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the deployment-global physical repository key contract introduced by V36. */
@SpringBootTest(
        classes = GitHubRepositoryImportSchemaM8Q02IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false"
        })
class GitHubRepositoryImportSchemaM8Q02IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void repositoryKeyIsUniqueAcrossTheDeployment() {
        String definition = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(constraint_row.oid)
                FROM pg_constraint constraint_row
                JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
                WHERE schema_row.nspname = 'crewscope'
                  AND table_row.relname = 'github_repository_import_job'
                  AND constraint_row.conname = 'uk_github_import_repository_key'
                """, String.class);
        Integer legacyConstraintCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_constraint constraint_row
                JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
                WHERE schema_row.nspname = 'crewscope'
                  AND table_row.relname = 'github_repository_import_job'
                  AND constraint_row.conname = 'uk_github_import_target'
                """, Integer.class);

        assertEquals("UNIQUE (repository_key)", definition);
        assertEquals(0, legacyConstraintCount);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication { }
}
