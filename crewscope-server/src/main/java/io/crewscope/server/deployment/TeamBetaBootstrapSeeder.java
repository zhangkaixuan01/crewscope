package io.crewscope.server.deployment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Creates the minimum authority facts needed by a clean-host Worker after Flyway completes. Existing
 * coordinates are accepted only when every immutable bootstrap fact still matches.
 */
final class TeamBetaBootstrapSeeder {

    private final UUID organizationId;
    private final String organizationName;
    private final UUID runtimePrincipalId;

    TeamBetaBootstrapSeeder(
            String organizationId, String organizationName, String runtimePrincipalId) {
        this.organizationId = parse(organizationId, "organization ID");
        this.organizationName = requireName(organizationName);
        this.runtimePrincipalId = parse(runtimePrincipalId, "Runtime Principal ID");
        if (this.organizationId.equals(this.runtimePrincipalId)) {
            throw new IllegalArgumentException(
                    "Team Beta bootstrap Organization and Runtime Principal IDs must differ");
        }
    }

    void seed(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                insertOrganization(connection);
                verifyOrganization(connection);
                insertRuntimePrincipal(connection);
                verifyRuntimePrincipal(connection);
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw failure(exception);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private void insertOrganization(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO crewscope.organization (id, name, status)
                VALUES (?, ?, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setObject(1, organizationId);
            statement.setString(2, organizationName);
            statement.executeUpdate();
        }
    }

    private void verifyOrganization(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name, status FROM crewscope.organization WHERE id = ?
                """)) {
            statement.setObject(1, organizationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || !organizationName.equals(result.getString("name"))
                        || !"ACTIVE".equals(result.getString("status"))) {
                    throw new IllegalStateException(
                            "Team Beta bootstrap Organization conflicts with existing facts");
                }
            }
        }
    }

    private void insertRuntimePrincipal(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'SERVICE', 'CrewScope Team Beta Runtime', 'ORGANIZATION', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setObject(1, runtimePrincipalId);
            statement.setObject(2, organizationId);
            statement.executeUpdate();
        }
    }

    private void verifyRuntimePrincipal(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT organization_id, principal_type, display_name, visibility, status
                FROM crewscope.principal WHERE id = ?
                """)) {
            statement.setObject(1, runtimePrincipalId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || !organizationId.equals(result.getObject("organization_id", UUID.class))
                        || !"SERVICE".equals(result.getString("principal_type"))
                        || !"CrewScope Team Beta Runtime".equals(result.getString("display_name"))
                        || !"ORGANIZATION".equals(result.getString("visibility"))
                        || !"ACTIVE".equals(result.getString("status"))) {
                    throw new IllegalStateException(
                            "Team Beta Runtime Principal conflicts with existing facts");
                }
            }
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static UUID parse(String value, String name) {
        try {
            return UUID.fromString(Objects.requireNonNull(value, name).strip());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Team Beta bootstrap " + name + " is invalid");
        }
    }

    private static String requireName(String value) {
        String name = Objects.requireNonNull(value, "organizationName").strip();
        if (name.isEmpty() || name.length() > 200) {
            throw new IllegalArgumentException(
                    "Team Beta bootstrap Organization name must contain 1 to 200 characters");
        }
        return name;
    }

    private static IllegalStateException failure(Exception exception) {
        return new IllegalStateException("Team Beta bootstrap seeding failed", exception);
    }
}
