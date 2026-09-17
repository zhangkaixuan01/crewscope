package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies that V39 gives a comment its own update-command slot without touching existing rows. */
class V39ReviewLineCommentCommandKeyMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final MigrationVersion VERSION_38 = MigrationVersion.fromVersion("38");
    private static final MigrationVersion VERSION_39 = MigrationVersion.fromVersion("39");

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void addsTheUpdateCommandSlotWithoutMovingExistingCreateKeys() throws SQLException {
        flyway(VERSION_38).migrate();
        UUID organizationId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        insertComment(organizationId, commentId, "create-key-1");

        flyway(VERSION_39).migrate();

        assertEquals(1, queryInt("SELECT COUNT(*) FROM crewscope.review_line_comment"));
        assertEquals("create-key-1", queryString(
                "SELECT idempotency_key FROM crewscope.review_line_comment WHERE id = ?", commentId));
        assertNull(queryString(
                "SELECT last_command_idempotency_key FROM crewscope.review_line_comment WHERE id = ?",
                commentId));
    }

    @Test
    void scopesTheUpdateCommandSlotPerOrganizationAndRejectsAFreeUpdate() throws SQLException {
        flyway(VERSION_39).migrate();
        UUID organizationId = UUID.randomUUID();
        UUID editedComment = UUID.randomUUID();
        UUID otherComment = UUID.randomUUID();
        insertComment(organizationId, editedComment, "create-key-1");
        insertComment(organizationId, otherComment, "create-key-2");

        // Neither comment has been edited yet, and both may share the empty slot.
        assertEquals(2, queryInt(
                "SELECT COUNT(*) FROM crewscope.review_line_comment WHERE last_command_idempotency_key IS NULL"));

        executeUpdate(
                "UPDATE crewscope.review_line_comment SET last_command_idempotency_key = 'edit-key-1' WHERE id = ?",
                editedComment);
        assertEquals("edit-key-1", queryString(
                "SELECT last_command_idempotency_key FROM crewscope.review_line_comment WHERE id = ?",
                editedComment));
        assertEquals("create-key-1", queryString(
                "SELECT idempotency_key FROM crewscope.review_line_comment WHERE id = ?", editedComment),
                "writing the update command key must leave the create key where it was");

        assertThrows(
                SQLException.class,
                () -> executeUpdate(
                        "UPDATE crewscope.review_line_comment SET last_command_idempotency_key = 'edit-key-1' WHERE id = ?",
                        otherComment),
                "two comments in one organization must not share an update command key");
        assertThrows(
                SQLException.class,
                () -> executeUpdate(
                        "UPDATE crewscope.review_line_comment SET last_command_idempotency_key = 'bad key' WHERE id = ?",
                        otherComment),
                "an update command key has to match the idempotency key grammar");
    }

    @Test
    void publishesTheColumnConstraintAndPartialIndex() throws SQLException {
        flyway(VERSION_39).migrate();

        assertTrue(queryStrings(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'crewscope' AND table_name = 'review_line_comment'
                """).contains("last_command_idempotency_key"));
        assertTrue(queryStrings(
                """
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE constraint_schema = 'crewscope' AND table_name = 'review_line_comment'
                """).contains("ck_review_line_comment_last_command_key"));
        assertTrue(queryStrings(
                """
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'crewscope' AND tablename = 'review_line_comment'
                """).containsAll(Set.of(
                        "uk_review_line_comment_idempotency",
                        "uk_review_line_comment_last_command_key")));
    }

    /**
     * The receipt columns are the subject here, so the comment is written without the ReviewRequest
     * and Principal graph that the production path creates first.
     */
    private static void insertComment(UUID organizationId, UUID commentId, String createKey)
            throws SQLException {
        try (Connection connection = openConnection();
                Statement role = connection.createStatement()) {
            role.execute("SET session_replication_role = replica");
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO crewscope.review_line_comment (
                        id, organization_id, team_id, workspace_id, project_id, task_id,
                        task_execution_id, attempt, review_request_id, file_path, side, line_number,
                        hunk_header, line_content_hash, diff_generation, content, author_principal_id,
                        anchor_state, deleted, version, idempotency_key, created_at,
                        created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 'src/main/java/App.java', 'NEW', 100,
                        '@@ -40 +100 @@', ?, 1, '已确认：这一行保留了旧分支的空值判断', ?, 'ACTIVE',
                        FALSE, 0, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
                    """)) {
                statement.setObject(1, commentId);
                statement.setObject(2, organizationId);
                for (int index = 3; index <= 8; index++) {
                    statement.setObject(index, UUID.randomUUID());
                }
                statement.setObject(9, "a".repeat(64));
                statement.setObject(10, UUID.randomUUID());
                statement.setObject(11, createKey);
                statement.setObject(12, UUID.randomUUID());
                statement.setObject(13, UUID.randomUUID());
                statement.executeUpdate();
            }
        }
    }

    private static Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int executeUpdate(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static int queryInt(String sql) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static String queryString(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static Set<String> queryStrings(String sql) throws SQLException {
        Set<String> values = new HashSet<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }
}
