package io.crewscope.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the V67 work_item_relation table: schema shape plus the backstop constraints —
 * in particular the undirected partial unique index that keeps opposite blocking edges from
 * coexisting (ADR-033).
 */
class V67WorkItemRelationMigrationIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final String SCHEMA = "crewscope";
    private static final MigrationVersion VERSION_67 = MigrationVersion.fromVersion("67");

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        }
    }

    @Test
    void createsTheRelationTableWithDirectionalBackstops() throws SQLException {
        Flyway flyway = flyway(VERSION_67);
        flyway.migrate();

        assertEquals("67", flyway.info().current().getVersion().getVersion());

        Set<String> constraints = queryStrings(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = 'crewscope'
                  AND table_name = 'work_item_relation'
                """);
        assertTrue(constraints.contains("uk_work_item_relation_scope_id"));
        assertTrue(constraints.contains("fk_work_item_relation_src"));
        assertTrue(constraints.contains("fk_work_item_relation_dst"));
        assertTrue(constraints.contains("fk_work_item_relation_created_by"));
        assertTrue(constraints.contains("fk_work_item_relation_updated_by"));
        assertTrue(constraints.contains("ck_work_item_relation_kind"));
        assertTrue(constraints.contains("ck_work_item_relation_not_self"));
        assertTrue(constraints.contains("ck_work_item_relation_immutable_audit"));

        assertEquals("NO", queryString(
                """
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'crewscope'
                  AND table_name = 'work_item_relation'
                  AND column_name = 'relation_kind'
                """));

        String forward = indexDefinition("ux_work_item_relation_forward");
        assertTrue(forward.startsWith("CREATE UNIQUE INDEX"));
        assertTrue(forward.contains("(src_work_item_id, dst_work_item_id)"));
        // the undirected pair guard is the only index-level defense against opposite edges;
        // pg_get_indexdef renders least/greatest as the keywords LEAST/GREATEST
        String blockPair = indexDefinition("ux_work_item_relation_block_pair");
        assertTrue(blockPair.startsWith("CREATE UNIQUE INDEX"));
        assertTrue(blockPair.toLowerCase(java.util.Locale.ROOT)
                .contains("least(src_work_item_id, dst_work_item_id)"));
        assertTrue(blockPair.toLowerCase(java.util.Locale.ROOT)
                .contains("greatest(src_work_item_id, dst_work_item_id)"));
        // PG 17 deparses the varchar predicate as ((relation_kind)::text = 'BLOCKS'::text);
        // match the full predicate shape instead of guessing the cast suffix
        assertTrue(blockPair.toLowerCase(java.util.Locale.ROOT)
                .contains("where ((relation_kind)::text = 'blocks'"));
        String splitChild = indexDefinition("ux_work_item_relation_split_child");
        assertTrue(splitChild.startsWith("CREATE UNIQUE INDEX"));
        assertTrue(splitChild.contains("(src_work_item_id)"));
        assertTrue(splitChild.toLowerCase(java.util.Locale.ROOT)
                .contains("where ((relation_kind)::text = 'split_from'"));
        // the reverse index is a plain walk index, not a uniqueness mechanism
        String reverse = indexDefinition("ix_work_item_relation_reverse");
        assertFalse(reverse.startsWith("CREATE UNIQUE INDEX"));
        assertTrue(reverse.contains("(dst_work_item_id, src_work_item_id)"));
        assertTrue(indexDefinition("ix_work_item_relation_team_kind")
                .contains("(organization_id, team_id, relation_kind, created_at DESC, id DESC)"));
    }

    @Test
    void databaseBackstopRejectsOppositeEdgesDuplicatesAndSecondSplitSources()
            throws SQLException {
        Flyway flyway = flyway(VERSION_67);
        flyway.migrate();
        Seed seed = seedWorkItems();

        // stored BLOCKS(A -> B)
        insertRelation(seed, seed.itemA, seed.itemB, "BLOCKS", seed.principalId);
        // the same direction again hits the forward unique index
        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertRelation(
                                seed, seed.itemA, seed.itemB, "BLOCKS", seed.principalId))
                .getMessage()
                .contains("ux_work_item_relation_forward"));
        // the opposite edge B -> A hits the undirected pair index
        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertRelation(
                                seed, seed.itemB, seed.itemA, "BLOCKS", seed.principalId))
                .getMessage()
                .contains("ux_work_item_relation_block_pair"));

        // one SPLIT_FROM source per child; a second source hits the partial unique index
        insertRelation(seed, seed.itemC, seed.itemD, "SPLIT_FROM", seed.principalId);
        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertRelation(
                                seed, seed.itemC, seed.itemB, "SPLIT_FROM", seed.principalId))
                .getMessage()
                .contains("ux_work_item_relation_split_child"));

        // self edges and the submission-only kind are rejected by CHECK constraints
        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertRelation(
                                seed, seed.itemA, seed.itemA, "BLOCKS", seed.principalId))
                .getMessage()
                .contains("ck_work_item_relation_not_self"));
        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertRelation(
                                seed, seed.itemD, seed.itemC, "DEPENDS_ON", seed.principalId))
                .getMessage()
                .contains("ck_work_item_relation_kind"));
        // the row is an immutable fact: updated_by must equal created_by
        assertTrue(assertThrows(
                        SQLException.class,
                        () -> insertRelation(
                                seed, seed.itemC, seed.itemA, "BLOCKS", seed.secondPrincipalId))
                .getMessage()
                .contains("ck_work_item_relation_immutable_audit"));
    }

    private record Seed(
            UUID organizationId,
            UUID principalId,
            UUID secondPrincipalId,
            UUID itemA,
            UUID itemB,
            UUID itemC,
            UUID itemD) {}

    private Seed seedWorkItems() throws SQLException {
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        UUID secondPrincipalId = UUID.randomUUID();
        UUID itemA = UUID.randomUUID();
        UUID itemB = UUID.randomUUID();
        UUID itemC = UUID.randomUUID();
        UUID itemD = UUID.randomUUID();
        execute(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId);
        execute(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Team', 'ACTIVE')
                """,
                teamId,
                organizationId);
        execute(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Workspace', 'ACTIVE')
                """,
                workspaceId,
                organizationId,
                teamId);
        execute(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES (?, ?, ?, ?, 'CRW', 'Project')
                """,
                projectId,
                organizationId,
                teamId,
                workspaceId);
        for (UUID principal : new UUID[] {principalId, secondPrincipalId}) {
            execute(
                    """
                    INSERT INTO crewscope.principal (
                        id, organization_id, principal_type, display_name, status
                    ) VALUES (?, ?, 'USER', 'Actor', 'ACTIVE')
                    """,
                    principal,
                    organizationId);
        }
        UUID[] itemIds = {itemA, itemB, itemC, itemD};
        String[] itemKeys = {"CRW-1", "CRW-2", "CRW-3", "CRW-4"};
        for (int index = 0; index < itemIds.length; index++) {
            execute(
                    """
                    INSERT INTO crewscope.work_item (
                        id, organization_id, team_id, workspace_id, project_id,
                        item_key, item_type, title, status, priority
                    ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, 'BACKLOG', 'MEDIUM')
                    """,
                    itemIds[index],
                    organizationId,
                    teamId,
                    workspaceId,
                    projectId,
                    itemKeys[index],
                    "Task " + itemKeys[index]);
        }
        return new Seed(
                organizationId, principalId, secondPrincipalId, itemA, itemB, itemC, itemD);
    }

    private static void insertRelation(
            Seed seed, UUID src, UUID dst, String kind, UUID updatedBy) throws SQLException {
        execute(
                """
                INSERT INTO crewscope.work_item_relation (
                    id, organization_id, team_id,
                    src_workspace_id, src_project_id, src_work_item_id,
                    dst_workspace_id, dst_project_id, dst_work_item_id,
                    relation_kind, created_by_principal_id, updated_by_principal_id
                ) SELECT ?, ?, team_id, workspace_id, project_id, ?,
                         workspace_id, project_id, ?, ?, ?, ?
                  FROM crewscope.work_item WHERE id = ?
                """,
                UUID.randomUUID(),
                seed.organizationId(),
                src,
                dst,
                kind,
                seed.principalId(),
                updatedBy,
                src);
    }

    private static Flyway flyway(MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
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

    private static int execute(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static String indexDefinition(String indexName) throws SQLException {
        return queryString(
                """
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'crewscope' AND indexname = '%s'
                """.formatted(indexName));
    }

    private static Set<String> queryStrings(String sql) throws SQLException {
        java.util.HashSet<String> values = new java.util.HashSet<>();
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
