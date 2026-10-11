package io.crewscope.infrastructure.persistence.workgraph;

import io.crewscope.application.workgraph.BlockedItemPosition;
import io.crewscope.application.workgraph.BlockedUpstreamClosure;
import io.crewscope.application.workgraph.BlockedWorkItemPage;
import io.crewscope.application.workgraph.BlockedWorkItemRow;
import io.crewscope.application.workgraph.WorkItemRelationGraphRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
 * deduplication alone. The presentation reads — {@link #upstreamChain}, {@link
 * #upstreamClosure} and {@link #findCurrentlyBlocked} — apply the frozen depth 32 / row 5000
 * bounds (blocker window 20) and report truncation explicitly.
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

    /**
     * Bounded upstream blocking closure of {@code :node} as CriticalPath adjacency: one row per
     * (blocked, blocker, depth) triple, the blocker sitting {@code depth} hops above the seed.
     * UNION deduplicates whole triples, so a diamond blocker can reappear at several depths —
     * the adjacency assembly below collapses those to one pair per (blocked, blocker).
     */
    private static final String BOUNDED_UPSTREAM_CLOSURE =
            """
            WITH RECURSIVE upstream(blocked, blocker, depth) AS (
                SELECT dst_work_item_id, src_work_item_id, 1 FROM crewscope.work_item_relation
                 WHERE organization_id = :organizationId
                   AND dst_work_item_id = :node AND relation_kind = 'BLOCKS'
                UNION
                SELECT d.dst_work_item_id, d.src_work_item_id, u.depth + 1
                  FROM crewscope.work_item_relation d
                  JOIN upstream u ON d.dst_work_item_id = u.blocker
                 WHERE d.organization_id = :organizationId
                   AND d.relation_kind = 'BLOCKS' AND u.depth < 32
            )
            SELECT blocked, blocker, depth FROM upstream
             ORDER BY depth, blocked, blocker LIMIT 5001
            """;

    /**
     * "Currently blocked" page query (ADR-033): groups the stored BLOCKS in-edges by blocked
     * WorkItem after dropping in-edges whose src ended execution. {@code blocked_since} is the
     * newest still-blocking in-edge; the row-value {@code HAVING} below is the exclusive keyset
     * restart for the {@code (blocked_since DESC, work_item_id DESC)} order.
     */
    private static final String CURRENTLY_BLOCKED_PAGE =
            """
            SELECT e.dst_work_item_id AS work_item_id, w.item_key, w.title, w.status, w.project_id,
                   MAX(e.created_at) AS blocked_since, COUNT(*) AS blocker_count,
                   BOOL_OR(e.src_project_id <> e.dst_project_id) AS crosses_project
              FROM crewscope.work_item_relation e
              JOIN crewscope.work_item blocker
                ON blocker.organization_id = e.organization_id AND blocker.id = e.src_work_item_id
              JOIN crewscope.work_item w
                ON w.organization_id = e.organization_id AND w.id = e.dst_work_item_id
             WHERE e.organization_id = :organizationId
               AND e.team_id = :teamId
               AND e.relation_kind = 'BLOCKS'
               AND blocker.status NOT IN (:terminalStatuses)
            """;

    /**
     * Optional blocked-side project filter; binds between the WHERE block and the GROUP BY.
     * Every optional fragment carries its own trailing blank so any splice stays valid SQL.
     */
    private static final String CURRENTLY_BLOCKED_PAGE_PROJECT = " AND e.dst_project_id = :projectId ";

    private static final String CURRENTLY_BLOCKED_PAGE_GROUP =
            "GROUP BY e.dst_work_item_id, w.item_key, w.title, w.status, w.project_id ";

    /** Exclusive keyset predicate on the page order {@code (blocked_since DESC, work_item_id DESC)}. */
    private static final String CURRENTLY_BLOCKED_PAGE_AFTER =
            "HAVING (MAX(e.created_at), e.dst_work_item_id) < (:afterBlockedSince, :afterWorkItemId) ";

    private static final String CURRENTLY_BLOCKED_PAGE_ORDER =
            """
            ORDER BY MAX(e.created_at) DESC, e.dst_work_item_id DESC
            LIMIT :limit
            """;

    /**
     * Blocker summary window for one page's blocked WorkItems: the newest
     * {@link BlockedWorkItemRow#MAX_BLOCKERS} executing blockers per row, filtering exactly like
     * the page query so the window can never disagree with {@code blocker_count}.
     */
    private static final String CURRENTLY_BLOCKED_BLOCKER_WINDOW =
            """
            SELECT ranked.work_item_id, ranked.blocker_id, w.title, w.status, w.project_id
              FROM (
                SELECT e.dst_work_item_id AS work_item_id, e.src_work_item_id AS blocker_id,
                       ROW_NUMBER() OVER (PARTITION BY e.dst_work_item_id
                           ORDER BY e.created_at DESC, e.src_work_item_id DESC) AS rn
                  FROM crewscope.work_item_relation e
                  JOIN crewscope.work_item blocker
                    ON blocker.organization_id = e.organization_id
                   AND blocker.id = e.src_work_item_id
                 WHERE e.organization_id = :organizationId
                   AND e.team_id = :teamId
                   AND e.relation_kind = 'BLOCKS'
                   AND blocker.status NOT IN (:terminalStatuses)
                   AND e.dst_work_item_id IN (:workItemIds)
              ) ranked
              JOIN crewscope.work_item w
                ON w.organization_id = :organizationId AND w.id = ranked.blocker_id
             WHERE ranked.rn <= 20
             ORDER BY ranked.work_item_id, ranked.rn
            """;

    /**
     * Terminal src statuses bound into the "currently blocked" SQL, derived from
     * {@link WorkItemStatus#isTerminal()} — the single authority, so the derived aggregation and
     * the domain can never drift about which WorkItems still execute.
     */
    private static final List<String> TERMINAL_STATUSES =
            Arrays.stream(WorkItemStatus.values())
                    .filter(WorkItemStatus::isTerminal)
                    .map(Enum::name)
                    .toList();

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

    @Override
    @Transactional
    public BlockedUpstreamClosure upstreamClosure(OrganizationId organizationId, WorkItemId node) {
        List<ClosureRow> rows =
                jdbcTemplate.query(
                        BOUNDED_UPSTREAM_CLOSURE,
                        new MapSqlParameterSource()
                                .addValue(
                                        "organizationId",
                                        Objects.requireNonNull(organizationId).value())
                                .addValue("node", Objects.requireNonNull(node, "node").value()),
                        (rs, rowNum) ->
                                new ClosureRow(
                                        new WorkItemId(rs.getObject("blocked", UUID.class)),
                                        new WorkItemId(rs.getObject("blocker", UUID.class)),
                                        rs.getInt("depth")));
        boolean truncatedByRows = rows.size() > BlockingChain.MAX_ROWS;
        List<ClosureRow> retained =
                truncatedByRows ? rows.subList(0, BlockingChain.MAX_ROWS) : rows;
        boolean truncatedByDepth =
                retained.stream().anyMatch(row -> row.depth() == BlockingChain.MAX_DEPTH);
        Map<WorkItemId, LinkedHashSet<WorkItemId>> pairs = new LinkedHashMap<>();
        retained.forEach(
                row ->
                        pairs.computeIfAbsent(row.blocked(), blocked -> new LinkedHashSet<>())
                                .add(row.blocker()));
        Map<WorkItemId, List<WorkItemId>> upstreamOf = new LinkedHashMap<>();
        pairs.forEach((blocked, blockers) -> upstreamOf.put(blocked, List.copyOf(blockers)));
        return new BlockedUpstreamClosure(upstreamOf, truncatedByDepth, truncatedByRows);
    }

    @Override
    @Transactional
    public BlockedWorkItemPage findCurrentlyBlocked(
            OrganizationId organizationId,
            TeamId teamId,
            Optional<WorkProjectId> projectId,
            Optional<BlockedItemPosition> after,
            int limit) {
        MapSqlParameterSource pageParameters =
                new MapSqlParameterSource()
                        .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                        .addValue("teamId", Objects.requireNonNull(teamId, "teamId").value())
                        .addValue("terminalStatuses", TERMINAL_STATUSES)
                        // one row past the page only detects that a next page exists
                        .addValue("limit", limit + 1);
        projectId.ifPresent(filtered -> pageParameters.addValue("projectId", filtered.value()));
        after.ifPresent(
                position ->
                        pageParameters
                                .addValue(
                                        "afterBlockedSince",
                                        position.blockedSince().toOffsetDateTime())
                                .addValue("afterWorkItemId", position.workItemId().value()));
        List<PageRow> fetched =
                jdbcTemplate.query(
                        CURRENTLY_BLOCKED_PAGE
                                + (projectId.isPresent() ? CURRENTLY_BLOCKED_PAGE_PROJECT : "")
                                + CURRENTLY_BLOCKED_PAGE_GROUP
                                + (after.isPresent() ? CURRENTLY_BLOCKED_PAGE_AFTER : "")
                                + CURRENTLY_BLOCKED_PAGE_ORDER,
                        pageParameters,
                        (rs, rowNum) -> pageRow(rs));
        List<PageRow> page = fetched.size() > limit ? fetched.subList(0, limit) : fetched;
        // the restart position is the LAST returned row: the exclusive keyset predicate
        // drops it and everything before it, so the next page starts exactly one row later
        Optional<BlockedItemPosition> nextPosition =
                fetched.size() > limit
                        ? Optional.of(
                                new BlockedItemPosition(
                                        page.get(page.size() - 1).blockedSince(),
                                        page.get(page.size() - 1).workItemId()))
                        : Optional.empty();
        return new BlockedWorkItemPage(assembleBlockers(organizationId, teamId, page), nextPosition);
    }

    /** Runs the blocker window for the page's blocked WorkItems and joins it onto the rows. */
    private List<BlockedWorkItemRow> assembleBlockers(
            OrganizationId organizationId, TeamId teamId, List<PageRow> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        List<WindowRow> window =
                jdbcTemplate.query(
                        CURRENTLY_BLOCKED_BLOCKER_WINDOW,
                        new MapSqlParameterSource()
                                .addValue("organizationId", organizationId.value())
                                .addValue("teamId", teamId.value())
                                .addValue("terminalStatuses", TERMINAL_STATUSES)
                                .addValue(
                                        "workItemIds",
                                        page.stream()
                                                .map(row -> row.workItemId().value())
                                                .toList()),
                        (rs, rowNum) ->
                                new WindowRow(
                                        new WorkItemId(rs.getObject("work_item_id", UUID.class)),
                                        new BlockedWorkItemRow.Blocker(
                                                new WorkItemId(rs.getObject("blocker_id", UUID.class)),
                                                rs.getString("title"),
                                                WorkItemStatus.valueOf(rs.getString("status")),
                                                new WorkProjectId(
                                                        rs.getObject("project_id", UUID.class)))));
        Map<WorkItemId, List<BlockedWorkItemRow.Blocker>> blockers = new LinkedHashMap<>();
        window.forEach(
                row ->
                        blockers
                                .computeIfAbsent(row.workItemId(), key -> new ArrayList<>())
                                .add(row.blocker()));
        return page.stream()
                .map(
                        row ->
                                new BlockedWorkItemRow(
                                        row.workItemId(),
                                        row.itemKey(),
                                        row.title(),
                                        row.status(),
                                        row.projectId(),
                                        row.blockedSince(),
                                        row.blockerCount(),
                                        row.crossesProject(),
                                        blockers.getOrDefault(row.workItemId(), List.of())))
                .toList();
    }

    private static PageRow pageRow(ResultSet rs) throws SQLException {
        return new PageRow(
                new WorkItemId(rs.getObject("work_item_id", UUID.class)),
                rs.getString("item_key"),
                rs.getString("title"),
                WorkItemStatus.valueOf(rs.getString("status")),
                new WorkProjectId(rs.getObject("project_id", UUID.class)),
                UtcTimestamp.from(rs.getObject("blocked_since", OffsetDateTime.class)),
                rs.getInt("blocker_count"),
                rs.getBoolean("crosses_project"));
    }

    /** Raw page-query row before the blocker window is joined on. */
    private record PageRow(
            WorkItemId workItemId,
            String itemKey,
            String title,
            WorkItemStatus status,
            WorkProjectId projectId,
            UtcTimestamp blockedSince,
            int blockerCount,
            boolean crossesProject) {}

    /** One blocker-window entry keyed by the blocked WorkItem it belongs to. */
    private record WindowRow(WorkItemId workItemId, BlockedWorkItemRow.Blocker blocker) {}

    /** One closure CTE row before duplicates collapse into the adjacency map. */
    private record ClosureRow(WorkItemId blocked, WorkItemId blocker, int depth) {}
}
