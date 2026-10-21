package io.crewscope.infrastructure.testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Database-per-test leasing on top of the shared singleton containers.
 *
 * <p>The JDBC-style migration integration tests used to replay the whole
 * {@code db/migration} chain per test method (DROP SCHEMA, then every migration
 * from V1 up, ~3.2s each — 569 Flyway executions per full run). This class replaces
 * that with PostgreSQL physical clones: once per JVM and per tier it migrates a seed
 * database up to the requested version, verifies the seed's
 * {@code flyway_schema_history} row count, then hands every test method a private
 * {@code CREATE DATABASE ... TEMPLATE <seed>} copy — the identical schema at a
 * fraction of the cost, with stronger isolation than the old reset: a fresh
 * database cannot carry any cross-method leftover at all.
 *
 * <p>Lease tiers: a numeric tier ({@code "31"}) clones a seed migrated exactly to
 * V31, with the history row count pinned to 31 as the seed's own tripwire;
 * {@link #TIP} clones the default chain's tip; {@link #BLANK} hands out an empty
 * database for tests that assert the full replay themselves. Seeds are built
 * lazily under one JVM-wide lock — surefire runs test methods serially inside a
 * fork and each fork owns its container, so a single lock has no cross-fork
 * contention. Once built a seed is immutable, so cloning never takes the lock.
 *
 * <p>Administration runs over per-statement connections to the {@code postgres}
 * database ({@code CREATE DATABASE} cannot run inside a transaction block), and
 * drops carry {@code WITH (FORCE)} to backstop leaked connections. Seeds and
 * leased databases live inside the container, so Ryuk reclaims everything with
 * the JVM — no cleanup hooks.
 */
public final class SchemaTemplates {

    /** Tier for an empty database: the caller replays migrations itself. */
    public static final String BLANK = "blank";

    /** Tier for the default chain's tip (whatever the classpath currently carries). */
    public static final String TIP = "tip";

    private static final Map<JdbcDatabaseContainer<?>, SchemaTemplates> BY_CONTAINER =
            new ConcurrentHashMap<>();

    private final JdbcDatabaseContainer<?> container;
    private final String jdbcHead;
    private final String jdbcQuery;
    private final Object seedLock = new Object();
    private final Map<String, Boolean> readySeeds = new ConcurrentHashMap<>();

    private SchemaTemplates(JdbcDatabaseContainer<?> container) {
        this.container = container;
        String url = container.getJdbcUrl();
        int pathStart = url.indexOf('/', "jdbc:postgresql://".length());
        if (pathStart < 0) {
            throw new IllegalStateException("Unrecognized JDBC URL: " + url);
        }
        int queryStart = url.indexOf('?', pathStart);
        this.jdbcHead = url.substring(0, pathStart);
        this.jdbcQuery = queryStart >= 0 ? url.substring(queryStart) : "";
    }

    /** Templates bound to the shared {@code postgres:17-alpine} container. */
    public static SchemaTemplates forPostgres() {
        return forContainer(AbstractPostgresRedisContainerIntegrationTest.POSTGRES);
    }

    /** Templates bound to the shared {@code pgvector/pgvector:pg17} container. */
    public static SchemaTemplates forPgVector() {
        return forContainer(AbstractPgVectorContainerIntegrationTest.PGVECTOR);
    }

    /** Templates bound to any container, e.g. a test's own self-managed instance. */
    public static SchemaTemplates forContainer(JdbcDatabaseContainer<?> container) {
        return BY_CONTAINER.computeIfAbsent(container, SchemaTemplates::new);
    }

    /**
     * Leases a private database: a physical clone of the tier's seed (built on
     * first use), or an empty database for {@link #BLANK}. Closing the lease drops
     * the database.
     */
    public Lease lease(String tier) {
        String database = newDatabaseName();
        if (BLANK.equals(tier)) {
            createDatabase(database, null);
        } else {
            String seed = ensureSeed(tier);
            terminateBackends(seed);
            createDatabase(database, seed);
        }
        return new Lease(this, database);
    }

    /** Builds (once per tier) and verifies the seed database, returning its name. */
    private String ensureSeed(String tier) {
        if (readySeeds.containsKey(tier)) {
            return seedName(tier);
        }
        synchronized (seedLock) {
            if (readySeeds.containsKey(tier)) {
                return seedName(tier);
            }
            String seed = seedName(tier);
            // A half-built seed (crashed JVM, failed verification) must not survive.
            dropDatabase(seed);
            createDatabase(seed, null);

            FluentConfiguration configuration = Flyway.configure()
                    .dataSource(urlFor(seed), container.getUsername(), container.getPassword())
                    .locations("classpath:db/migration")
                    .schemas("crewscope")
                    .defaultSchema("crewscope")
                    .createSchemas(true)
                    .validateMigrationNaming(true);
            if (!TIP.equals(tier)) {
                configuration.target(MigrationVersion.fromVersion(tier));
            }
            Flyway flyway = configuration.load();
            flyway.migrate();

            // Seed tripwire: the history table must show exactly the tier's version
            // count (numeric tiers) or a stable tip (a second migrate is a no-op),
            // otherwise every lease would inherit a mis-tiered schema.
            int historyRows = historyRowCount(seed);
            if (TIP.equals(tier)) {
                if (historyRows < 1 || flyway.migrate().migrationsExecuted != 0
                        || flyway.info().current() == null) {
                    throw new IllegalStateException(
                            "Seed " + seed + " did not converge at the chain tip");
                }
            } else {
                int expected = Integer.parseInt(tier);
                if (historyRows != expected) {
                    throw new IllegalStateException("Seed " + seed + " history holds "
                            + historyRows + " rows, expected " + expected);
                }
            }
            readySeeds.put(tier, Boolean.TRUE);
            return seed;
        }
    }

    private int historyRowCount(String database) {
        String url = urlFor(database);
        // createSchemas(true) leaves a version-less SCHEMA row (rank 0); only versioned
        // rows count, matching what migrate().migrationsExecuted reports.
        try (Connection connection = DriverManager.getConnection(
                url, container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM crewscope.flyway_schema_history"
                                + " WHERE version IS NOT NULL")) {
            result.next();
            return result.getInt(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot verify seed " + database, exception);
        }
    }

    private static String seedName(String tier) {
        return "seed_" + tier;
    }

    private static String newDatabaseName() {
        return "t_" + ProcessHandle.current().pid() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private void terminateBackends(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeQuery(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"
                            + database + "'");
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot quiesce " + database, exception);
        }
    }

    private void createDatabase(String database, String template) {
        String sql = "CREATE DATABASE " + database
                + (template == null ? "" : " TEMPLATE " + template);
        try (Connection connection = adminConnection()) {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot execute: " + sql, exception);
        }
    }

    private void dropDatabase(String database) {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot drop " + database, exception);
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                urlFor("postgres"), container.getUsername(), container.getPassword());
    }

    private String urlFor(String database) {
        return jdbcHead + "/" + database + jdbcQuery;
    }

    void release(Lease lease) {
        dropDatabase(lease.databaseName);
    }

    /** A private test database, dropped again on {@link #close()}. */
    public static final class Lease implements AutoCloseable {

        private final SchemaTemplates owner;
        private final String databaseName;
        private final String jdbcUrl;
        private boolean closed;

        private Lease(SchemaTemplates owner, String databaseName) {
            this.owner = owner;
            this.databaseName = databaseName;
            this.jdbcUrl = owner.urlFor(databaseName);
        }

        /** The leased database's JDBC URL, carrying the container's URL parameters. */
        public String jdbcUrl() {
            ensureOpen();
            return jdbcUrl;
        }

        /** The URL plus extra parameters ({@code currentSchema=...}), separator-aware. */
        public String jdbcUrl(String parameters) {
            ensureOpen();
            return jdbcUrl + (jdbcUrl.indexOf('?') >= 0 ? "&" : "?") + parameters;
        }

        public String username() {
            return owner.container.getUsername();
        }

        public String password() {
            return owner.container.getPassword();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.release(this);
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Lease on " + databaseName + " is closed");
            }
        }
    }
}
