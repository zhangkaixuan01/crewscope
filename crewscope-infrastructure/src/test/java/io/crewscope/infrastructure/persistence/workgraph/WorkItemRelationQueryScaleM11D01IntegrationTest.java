package io.crewscope.infrastructure.persistence.workgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.workgraph.WorkItemRelationGraphRepository;
import io.crewscope.application.workgraph.WorkItemRelationRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkProjectRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.WorkItemEntity;
import io.crewscope.infrastructure.persistence.workitem.WorkItemEntityMapper;
import io.crewscope.infrastructure.persistence.workitem.WorkPersistenceMapper;
import io.crewscope.infrastructure.persistence.workitem.WorkProjectEntity;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The M11-D01 scale baseline on real PostgreSQL at the ADR-033 thresholds: with 5300 items and
 * 4999 edges (a 2000-node chain plus a 300-hub fan-out), single-point adjacency holds p95
 * ≤ 100ms, the depth-bounded upstream walk holds p95 ≤ 500ms and never reports past depth 32,
 * and the 2000-deep chain says it was truncated instead of pretending to end.
 */
@SpringBootTest(
        classes = WorkItemRelationQueryScaleM11D01IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class WorkItemRelationQueryScaleM11D01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final int WARMUP = 10;
    private static final int SAMPLES = 20;
    private static final long ADJACENCY_BUDGET_MILLIS = 100;
    private static final long CHAIN_WALK_BUDGET_MILLIS = 500;

    @Autowired
    private WorkItemRelationRepository relationRepository;

    @Autowired
    private WorkItemRelationGraphRepository graphRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private M11D01GraphScaleFixtures.Graph graph;
    private OrganizationId organizationId;

    @BeforeEach
    void seedScaleGraph() {
        graph = new M11D01GraphScaleFixtures(jdbcTemplate).seed();
        organizationId = OrganizationId.from(graph.organizationId().toString());
        // the spike script's header says 5100/5099 but its SQL seeds 5300/4999; these
        // assertions pin the real totals past the 5000-node acceptance floor either way
        assertEquals(5_300, graph.items(), "the seeded graph must hold 5300 items");
        assertEquals(4_999, graph.edges(), "the seeded graph must hold 4999 edges");
    }

    /** p95 nearest-rank over a node mix: chain bottom, chain middle, hub, leaf. */
    @Test
    void adjacencyLookupsHoldTheLatencyBudgetAtScale() {
        List<WorkItemId> nodes =
                List.of(graph.chainBottom(), graph.chainMiddle(), graph.hub(), graph.leaf());
        for (int index = 0; index < WARMUP; index++) {
            relationRepository.findByEndpoint(organizationId, nodes.get(index % nodes.size()));
        }
        List<Long> samples = new ArrayList<>();
        for (int index = 0; index < SAMPLES; index++) {
            WorkItemId node = nodes.get(index % nodes.size());
            long startedAt = System.nanoTime();
            int adjacent = relationRepository.findByEndpoint(organizationId, node).size();
            samples.add((System.nanoTime() - startedAt) / 1_000_000);
            assertTrue(adjacent >= 1, "node " + node + " must have at least one edge");
        }
        long p95 = nearestRankP95(samples);
        assertTrue(
                p95 <= ADJACENCY_BUDGET_MILLIS,
                "adjacency lookups must hold p95 <= 100ms, measured p95 was " + p95 + "ms over "
                        + samples);
    }

    /**
     * The depth-bounded walk holds its budget and never reports past the depth bound — the
     * sampled roots mix the deepest possible walk (chain bottom, which truncates at 32) with
     * cheap ones so the p95 reflects the real read path, not only the worst corner.
     */
    @Test
    void upstreamChainWalksHoldTheLatencyBudgetAndDepthBoundAtScale() {
        List<WorkItemId> roots =
                List.of(graph.chainBottom(), graph.chainMiddle(), graph.hub(), graph.leaf());
        for (int index = 0; index < WARMUP; index++) {
            graphRepository.upstreamChain(organizationId, roots.get(index % roots.size()));
        }
        List<Long> samples = new ArrayList<>();
        for (int index = 0; index < SAMPLES; index++) {
            WorkItemId root = roots.get(index % roots.size());
            long startedAt = System.nanoTime();
            BlockingChain chain = graphRepository.upstreamChain(organizationId, root);
            samples.add((System.nanoTime() - startedAt) / 1_000_000);
            assertTrue(
                    chain.steps().size() <= BlockingChain.MAX_DEPTH,
                    "the walk must never report past depth " + BlockingChain.MAX_DEPTH
                            + ", got " + chain.steps().size());
        }
        long p95 = nearestRankP95(samples);
        assertTrue(
                p95 <= CHAIN_WALK_BUDGET_MILLIS,
                "upstream walks must hold p95 <= 500ms, measured p95 was " + p95 + "ms over "
                        + samples);
    }

    /** The 2000-deep chain must stop at depth 32 and say so — truncation is never silent. */
    @Test
    void theDeepChainReportsExplicitDepthTruncation() {
        BlockingChain chain = graphRepository.upstreamChain(organizationId, graph.chainBottom());

        assertEquals(BlockingChain.MAX_DEPTH, chain.steps().size());
        assertEquals(graph.chainBottom(), chain.root());
        // each step climbs one blocker: item_1's depth-32 step is item_33; the remaining
        // 1967 ancestors are cut by the bound and reported, not silently dropped
        assertEquals(
                M11D01GraphScaleFixtures.chainItem(1 + BlockingChain.MAX_DEPTH),
                chain.steps().get(BlockingChain.MAX_DEPTH - 1).node());
        assertEquals(
                BlockingChain.MAX_DEPTH,
                chain.steps().get(BlockingChain.MAX_DEPTH - 1).depth());
        assertTrue(chain.truncatedByDepth());
        assertFalse(chain.truncatedByRows());
    }

    private static long nearestRankP95(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compare);
        return sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {
        WorkItemRelationEntity.class, WorkProjectEntity.class, WorkItemEntity.class
    })
    @Import({
        JpaWorkItemRelationRepositoryAdapter.class,
        JdbcWorkItemRelationGraphAdapter.class,
        JpaWorkProjectRepositoryAdapter.class,
        JpaWorkItemRepositoryAdapter.class,
        WorkItemEntityMapper.class,
        WorkPersistenceMapper.class
    })
    static class TestApplication {}
}
