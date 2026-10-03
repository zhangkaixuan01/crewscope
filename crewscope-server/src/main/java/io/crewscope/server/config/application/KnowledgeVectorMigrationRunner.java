package io.crewscope.server.config.application;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Second, opt-in Flyway chain for the knowledge vector store (M10-I01a). The runner is
 * constructed unconditionally and gates itself with one rule: migrate and validate when
 * {@code crewscope.knowledge.vector.enabled=true} OR an earlier installation already
 * carries {@code crewscope.flyway_vector_history} — so an installed deployment keeps
 * validating and upgrading its vector chain even after the operator turns the flag off.
 *
 * <p>Ordering is structural: Boot migrates the default chain during bean initialization
 * and {@link SmartInitializingSingleton} runs after every singleton is instantiated, so
 * the vector chain always lands after the schema it depends on. Worker roles
 * ({@code spring.flyway.enabled=false}) skip like they do for the default chain. Any
 * Flyway failure is rethrown as an actionable {@link IllegalStateException} — never
 * swallowed.
 */
public final class KnowledgeVectorMigrationRunner implements SmartInitializingSingleton {

    private final DataSource dataSource;
    private final boolean vectorEnabled;
    private final boolean flywayEnabled;

    private volatile boolean applied;
    private volatile String skippedReason = "";

    public KnowledgeVectorMigrationRunner(
            DataSource dataSource, boolean vectorEnabled, boolean flywayEnabled) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.vectorEnabled = vectorEnabled;
        this.flywayEnabled = flywayEnabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!flywayEnabled) {
            skippedReason =
                    "spring.flyway.enabled=false (worker role): the vector chain migrates on"
                            + " the server role only";
            return;
        }
        boolean historyExists = vectorHistoryExists();
        if (!vectorEnabled && !historyExists) {
            skippedReason =
                    "crewscope.knowledge.vector.enabled=false and no flyway_vector_history:"
                            + " the optional vector store is not installed";
            return;
        }
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration-vector")
                    .schemas("crewscope")
                    .defaultSchema("crewscope")
                    .table("flyway_vector_history")
                    .validateMigrationNaming(true)
                    // The default chain owns schema creation and must have committed first.
                    .createSchemas(false)
                    // The vector chain is a later install onto a schema the default chain
                    // already filled: without a baseline Flyway refuses a non-empty schema
                    // that lacks this chain's history table. Baseline 0 (not Flyway's
                    // default 1) keeps V1 pending so the first run applies it.
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load()
                    .migrate();
            applied = true;
        } catch (FlywayException failure) {
            throw new IllegalStateException(
                    "Knowledge vector migration failed: verify the deployment runs the"
                            + " pgvector image (compose.pgvector.yaml), that the default"
                            + " migration chain succeeded and that no other node is"
                            + " migrating concurrently",
                    failure);
        }
    }

    /** Whether this process applied (or validated as current) the vector chain. */
    public boolean applied() {
        return applied;
    }

    /** Operator-facing reason when the chain did not run; empty once applied. */
    public String skippedReason() {
        return skippedReason;
    }

    private boolean vectorHistoryExists() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT to_regclass('crewscope.flyway_vector_history') IS NOT NULL")) {
            result.next();
            return result.getBoolean(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Knowledge vector history probe failed before migration: verify the"
                            + " default migration chain succeeded",
                    failure);
        }
    }
}
