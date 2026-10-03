package io.crewscope.server.config.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.server.observability.KnowledgeVectorHealthIndicator;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Assembly contract for the optional knowledge vector store (M10-I01a): the runner is
 * always constructed and gates itself; the store and indicator exist only behind the
 * opt-in flag; a worker role skips migration even when opted in; and a server role that
 * cannot migrate fails fast instead of starting half-installed.
 */
class KnowledgeVectorConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeVectorConfiguration.class)
            .withBean(DataSource.class, () -> stubDataSource(false))
            .withBean(
                    org.springframework.jdbc.core.JdbcTemplate.class,
                    () -> mock(org.springframework.jdbc.core.JdbcTemplate.class));

    @Test
    void disabledInstallsNoVectorBeansAndSkipsMigration() {
        runner.run(context -> {
            context.assertThat()
                    .hasNotFailed()
                    .doesNotHaveBean(KnowledgeEmbeddingVectorStore.class)
                    .doesNotHaveBean(KnowledgeVectorHealthIndicator.class)
                    .hasSingleBean(KnowledgeVectorMigrationRunner.class);
            KnowledgeVectorMigrationRunner migrationRunner =
                    context.getBean(KnowledgeVectorMigrationRunner.class);
            assertFalse(migrationRunner.applied());
            assertTrue(migrationRunner.skippedReason().contains("not installed"));
        });
    }

    @Test
    void enabledWorkersSkipMigrationButStillExposeTheOptedInBeans() {
        runner.withPropertyValues(
                        "crewscope.knowledge.vector.enabled=true",
                        "spring.flyway.enabled=false")
                .run(context -> {
                    context.assertThat()
                            .hasNotFailed()
                            .hasSingleBean(KnowledgeEmbeddingVectorStore.class)
                            .hasSingleBean(KnowledgeVectorHealthIndicator.class);
                    KnowledgeVectorMigrationRunner migrationRunner =
                            context.getBean(KnowledgeVectorMigrationRunner.class);
                    assertFalse(migrationRunner.applied());
                    assertTrue(migrationRunner.skippedReason().contains("worker"));
                    // Opted-in but not migrated is DOWN with the actionable reason.
                    KnowledgeVectorHealthIndicator indicator =
                            context.getBean(KnowledgeVectorHealthIndicator.class);
                    assertEquals(
                            org.springframework.boot.health.contributor.Status.DOWN,
                            indicator.health().getStatus());
                    assertTrue(
                            indicator.health().getDetails().toString().contains("worker"));
                });
    }

    @Test
    void anEnabledServerRoleThatCannotMigrateFailsFast() {
        // The stub DataSource answers the history probe with "absent" and then cannot
        // serve a real Flyway migration: startup must reject loudly.
        runner.withPropertyValues("crewscope.knowledge.vector.enabled=true")
                .run(context -> {
                    context.assertThat().hasFailed();
                    assertTrue(
                            context.getStartupFailure().getMessage().contains(
                                    "Knowledge vector migration failed"),
                            "failure must stay actionable: "
                                    + context.getStartupFailure().getMessage());
                });
    }

    @Test
    void anAppliedRunnerReportsHealthy() {
        KnowledgeVectorMigrationRunner migrationRunner =
                new KnowledgeVectorMigrationRunner(mock(DataSource.class), true, true);
        ReflectionTestUtils.setField(migrationRunner, "applied", true);
        ReflectionTestUtils.setField(migrationRunner, "skippedReason", "");

        KnowledgeVectorHealthIndicator indicator =
                new KnowledgeVectorHealthIndicator(migrationRunner);
        assertEquals(
                org.springframework.boot.health.contributor.Status.UP,
                indicator.health().getStatus());
        assertEquals("applied", indicator.health().getDetails().get("vectorMigration"));
    }

    /**
     * Answers the history probe with a fixed verdict; every later connection attempt
     * (a real Flyway migration) fails with SQLException so Flyway wraps it.
     */
    private static DataSource stubDataSource(boolean vectorHistoryExists) {
        try {
            ResultSet result = mock(ResultSet.class);
            when(result.next()).thenReturn(true);
            when(result.getBoolean(1)).thenReturn(vectorHistoryExists);
            Statement statement = mock(Statement.class);
            when(statement.executeQuery(anyString())).thenReturn(result);
            Connection connection = mock(Connection.class);
            when(connection.createStatement()).thenReturn(statement);
            DataSource dataSource = mock(DataSource.class);
            java.util.concurrent.atomic.AtomicInteger connections =
                    new java.util.concurrent.atomic.AtomicInteger();
            when(dataSource.getConnection())
                    .thenAnswer(
                            invocation -> {
                                if (connections.incrementAndGet() == 1) {
                                    return connection;
                                }
                                throw new SQLException("pgvector image is not running");
                            });
            return dataSource;
        } catch (SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
