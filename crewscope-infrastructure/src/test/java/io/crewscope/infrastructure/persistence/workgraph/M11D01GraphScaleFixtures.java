package io.crewscope.infrastructure.persistence.workgraph;

import io.crewscope.domain.workitem.WorkItemId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deterministic bulk graph for the M11-D01 scale verification, mirroring the S01b spike shape
 * (scripts/m11-s01/cte-depth.sql): a 2000-node blocking chain whose bottom node carries an
 * upstream depth of 1999 — far past the 32-deep walk bound — plus a 300-hub fan-out with ten
 * leaves each. Rows come from generate_series inserts, not per-row Java loops.
 *
 * <p>Stored direction follows ADR-033: src blocks dst, so every chain edge is
 * (src = item_{i+1}, dst = item_i) and upstreamChain(item_1) walks 1999 blockers. Leaf ids
 * encode their hub as h*100+j (the spike encoding), keeping ids readable and collision-free.
 */
public final class M11D01GraphScaleFixtures {

    private static final String CHAIN_PREFIX = "33333333-0000-0000-0000-";
    private static final String HUB_PREFIX = "44444444-0000-0000-0000-";
    private static final String LEAF_PREFIX = "55555555-0000-0000-0000-";
    private static final int CHAIN_LENGTH = 2_000;
    private static final int HUBS = 300;
    private static final int LEAVES_PER_HUB = 10;

    /** Where the probes point, plus the seeded totals for the sanity assertions. */
    public record Graph(
            UUID organizationId,
            UUID teamId,
            WorkItemId chainBottom,
            WorkItemId chainMiddle,
            WorkItemId hub,
            WorkItemId leaf,
            int items,
            int edges) {}

    private final JdbcTemplate jdbc;

    public M11D01GraphScaleFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Truncates and reseeds; 5300 items and 4999 edges in set-based inserts. */
    public Graph seed() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        UUID organizationId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId);
        jdbc.update(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Graph Scale Team', 'ACTIVE')
                """,
                teamId,
                organizationId);
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Graph Scale Workspace', 'ACTIVE')
                """,
                workspaceId,
                organizationId,
                teamId);
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name
                ) VALUES (?, ?, ?, ?, 'CRW', 'Graph Scale Project')
                """,
                projectId,
                organizationId,
                teamId,
                workspaceId);
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', 'Graph Scale Actor', 'ACTIVE')
                """,
                actorId,
                organizationId);

        // chain items, then chain edges: item_{i+1} blocks item_i, i = 1..1999
        insertItems(
                organizationId, teamId, workspaceId, projectId,
                """
                SELECT (?::text || lpad(i::text, 12, '0'))::uuid, 'CHAIN-' || i::text
                  FROM generate_series(1, %d) AS i
                """.formatted(CHAIN_LENGTH),
                CHAIN_PREFIX);
        insertEdges(
                organizationId, teamId, workspaceId, projectId, actorId,
                """
                SELECT (?::text || lpad((i + 1)::text, 12, '0'))::uuid AS src,
                       (?::text || lpad(i::text, 12, '0'))::uuid AS dst
                  FROM generate_series(1, %d) AS i
                """.formatted(CHAIN_LENGTH - 1),
                CHAIN_PREFIX, CHAIN_PREFIX);

        // hubs and their leaves (leaf id encodes h*100 + j), then fan edges: hub blocks leaf
        insertItems(
                organizationId, teamId, workspaceId, projectId,
                """
                SELECT (?::text || lpad(h::text, 12, '0'))::uuid, 'HUB-' || h::text
                  FROM generate_series(1, %d) AS h
                """.formatted(HUBS),
                HUB_PREFIX);
        insertItems(
                organizationId, teamId, workspaceId, projectId,
                """
                SELECT (?::text || lpad((h * 100 + j)::text, 12, '0'))::uuid,
                       'LEAF-' || (h * 100 + j)::text
                  FROM generate_series(1, %d) AS h, generate_series(1, %d) AS j
                """.formatted(HUBS, LEAVES_PER_HUB),
                LEAF_PREFIX);
        insertEdges(
                organizationId, teamId, workspaceId, projectId, actorId,
                """
                SELECT (?::text || lpad(h::text, 12, '0'))::uuid AS src,
                       (?::text || lpad((h * 100 + j)::text, 12, '0'))::uuid AS dst
                  FROM generate_series(1, %d) AS h, generate_series(1, %d) AS j
                """.formatted(HUBS, LEAVES_PER_HUB),
                HUB_PREFIX, LEAF_PREFIX);

        return new Graph(
                organizationId,
                teamId,
                chainItem(1),
                chainItem(CHAIN_LENGTH / 2),
                WorkItemId.from(HUB_PREFIX + pad(1)),
                WorkItemId.from(LEAF_PREFIX + pad(101)),
                count("crewscope.work_item"),
                count("crewscope.work_item_relation"));
    }

    static String pad(int value) {
        return String.format("%12d", value).replace(' ', '0');
    }

    static WorkItemId chainItem(int index) {
        return WorkItemId.from(CHAIN_PREFIX + pad(index));
    }

    /** Items from a (id, item_key) series; scope columns bind before the series' own literal. */
    private void insertItems(
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            String seriesSql,
            String idPrefix) {
        jdbc.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority
                ) SELECT series.id, ?, ?, ?, ?,
                          series.key, 'TASK', 'Scale ' || series.key, 'BACKLOG', 'MEDIUM'
                  FROM (%s) AS series(id, key)
                """.formatted(seriesSql),
                organizationId,
                teamId,
                workspaceId,
                projectId,
                idPrefix);
    }

    /** BLOCKS edges from a (src, dst) series; scope columns bind before the series' literals. */
    private void insertEdges(
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            UUID actorId,
            String seriesSql,
            String... idPrefixes) {
        List<Object> args =
                new ArrayList<>(
                        List.of(
                                organizationId,
                                teamId,
                                workspaceId,
                                projectId,
                                workspaceId,
                                projectId,
                                actorId,
                                actorId));
        args.addAll(List.of(idPrefixes));
        jdbc.update(
                """
                INSERT INTO crewscope.work_item_relation (
                    id, organization_id, team_id,
                    src_workspace_id, src_project_id, src_work_item_id,
                    dst_workspace_id, dst_project_id, dst_work_item_id,
                    relation_kind, created_by_principal_id, updated_by_principal_id
                ) SELECT gen_random_uuid(), ?, ?, ?, ?, series.src, ?, ?, series.dst,
                          'BLOCKS', ?, ?
                  FROM (%s) AS series(src, dst)
                """.formatted(seriesSql),
                args.toArray());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
