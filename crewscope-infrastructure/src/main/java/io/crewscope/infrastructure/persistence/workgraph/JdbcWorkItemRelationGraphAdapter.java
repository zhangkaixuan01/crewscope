package io.crewscope.infrastructure.persistence.workgraph;

import io.crewscope.application.workgraph.WorkItemRelationGraphRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recursive-CTE graph probes over stored WorkItemRelation edges (ADR-033).
 *
 * <p>Stored edges read {@code (src blocks dst)}; the split forest reads {@code (child, source)}.
 * Every recursive CTE here is written with {@code UNION} — never {@code UNION ALL}: on
 * multi-parent DAGs UNION ALL expands path counts exponentially and has taken down a real PG
 * backend, while UNION keeps the working set bounded by the node count.
 *
 * <p>The two reachability probes are correctness mechanisms and therefore carry <em>no</em>
 * depth or row bound — a truncated probe could admit a cycle. Termination comes from the UNION
 * deduplication alone. Only the presentation walk {@link #upstreamChain} applies the frozen
 * depth 32 / row 5000 bounds and reports truncation explicitly.
 */
@Repository
public class JdbcWorkItemRelationGraphAdapter implements WorkItemRelationGraphRepository {

    /**
     * Blocking closure of {@code :blocker}: everything it blocks directly or transitively.
     * Inserting {@code src BLOCKS dst} would close a cycle exactly when this closure of
     * {@code dst} contains {@code src} — the CTE must start at the new edge's blocked side.
     */
    private static final String BLOCKING_CLOSURE =
            """
            WITH RECURSIVE blocked_closure(node) AS (
                SELECT dst_work_item_id FROM crewscope.work_item_relation
                 WHERE organization_id = :organizationId
                   AND src_work_item_id = :start AND relation_kind = 'BLOCKS'
                UNION
                SELECT d.dst_work_item_id FROM crewscope.work_item_relation d
                 JOIN blocked_closure c ON d.src_work_item_id = c.node
                 WHERE d.organization_id = :organizationId AND d.relation_kind = 'BLOCKS'
            )
            SELECT EXISTS (SELECT 1 FROM blocked_closure WHERE node = :candidate)
            """;

    /** Ancestor chain of {@code :start} along SPLIT_FROM edges, for forest preservation. */
    private static final String SPLIT_ANCESTRY =
            """
            WITH RECURSIVE ancestors(node) AS (
                SELECT dst_work_item_id FROM crewscope.work_item_relation
                 WHERE organization_id = :organizationId
                   AND src_work_item_id = :start AND relation_kind = 'SPLIT_FROM'
                UNION
                SELECT d.dst_work_item_id FROM crewscope.work_item_relation d
                 JOIN ancestors a ON d.src_work_item_id = a.node
                 WHERE d.organization_id = :organizationId AND d.relation_kind = 'SPLIT_FROM'
            )
            SELECT EXISTS (SELECT 1 FROM ancestors WHERE node = :candidate)
            """;

    /**
     * Bounded upstream blocking walk (blocker chain of {@code :node}), depth capped at
     * {@link BlockingChain#MAX_DEPTH} and rows at {@link BlockingChain#MAX_ROWS}. UNION
     * deduplicates whole {@code (node, depth)} rows, so a diamond node can reappear at several
     * depths — an accepted presentation approximation; exact longest chains are the domain
     * CriticalPath algorithm's job, not this CTE's.
     */
    private static final String BOUNDED_UPSTREAM_WALK =
            """
            WITH RECURSIVE upstream(node, depth) AS (
                SELECT src_work_item_id, 1 FROM crewscope.work_item_relation
                 WHERE organization_id = :organizationId
                   AND dst_work_item_id = :node AND relation_kind = 'BLOCKS'
                UNION
                SELECT d.src_work_item_id, u.depth + 1 FROM crewscope.work_item_relation d
                 JOIN upstream u ON d.dst_work_item_id = u.node
                 WHERE d.organization_id = :organizationId
                   AND d.relation_kind = 'BLOCKS' AND u.depth < 32
            )
            SELECT node, depth FROM upstream ORDER BY depth, node LIMIT 5001
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcWorkItemRelationGraphAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    @Transactional
    public boolean blocksReaches(
            OrganizationId organizationId, WorkItemId blocker, WorkItemId candidate) {
        Boolean reachable =
                jdbcTemplate.queryForObject(
                        BLOCKING_CLOSURE,
                        reachParameters(organizationId, blocker, candidate),
                        Boolean.class);
        return Boolean.TRUE.equals(reachable);
    }

    @Override
    @Transactional
    public boolean splitAncestorOf(
            OrganizationId organizationId, WorkItemId start, WorkItemId candidate) {
        Boolean reachable =
                jdbcTemplate.queryForObject(
                        SPLIT_ANCESTRY,
                        reachParameters(organizationId, start, candidate),
                        Boolean.class);
        return Boolean.TRUE.equals(reachable);
    }

    @Override
    @Transactional
    public BlockingChain upstreamChain(OrganizationId organizationId, WorkItemId node) {
        List<BlockingChain.Step> rows =
                jdbcTemplate.query(
                        BOUNDED_UPSTREAM_WALK,
                        new MapSqlParameterSource()
                                .addValue(
                                        "organizationId",
                                        Objects.requireNonNull(organizationId).value())
                                .addValue("node", Objects.requireNonNull(node, "node").value()),
                        (rs, rowNum) ->
                                new BlockingChain.Step(
                                        new WorkItemId(rs.getObject("node", java.util.UUID.class)),
                                        rs.getInt("depth")));
        boolean truncatedByRows = rows.size() > BlockingChain.MAX_ROWS;
        List<BlockingChain.Step> steps =
                truncatedByRows ? rows.subList(0, BlockingChain.MAX_ROWS) : rows;
        boolean truncatedByDepth =
                steps.stream().anyMatch(step -> step.depth() == BlockingChain.MAX_DEPTH);
        return new BlockingChain(node, steps, truncatedByDepth, truncatedByRows);
    }

    private static MapSqlParameterSource reachParameters(
            OrganizationId organizationId, WorkItemId start, WorkItemId candidate) {
        return new MapSqlParameterSource()
                .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                .addValue("start", Objects.requireNonNull(start, "start").value())
                .addValue("candidate", Objects.requireNonNull(candidate, "candidate").value());
    }
}
