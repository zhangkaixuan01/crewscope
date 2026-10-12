package io.crewscope.infrastructure.persistence.workgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workgraph.BlockedUpstreamClosure;
import io.crewscope.application.workgraph.WorkItemRelationGraphRepository;
import io.crewscope.application.workgraph.WorkItemRelationRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workgraph.BlockingChain;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkProjectRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.WorkItemEntity;
import io.crewscope.infrastructure.persistence.workitem.WorkItemEntityMapper;
import io.crewscope.infrastructure.persistence.workitem.WorkPersistenceMapper;
import io.crewscope.infrastructure.persistence.workitem.WorkProjectEntity;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M11-Q01 fault-set face for the WorkGraph read side (ADR-033): a budget breach must fail
 * loudly, never answer a half-true chain. A session-scoped statement_timeout injected with
 * {@code SET LOCAL} — the same transaction, therefore the same pooled connection, as the
 * walk it cancels — makes the 2000-deep chain query die as a DataAccessException whose
 * cause carries PostgreSQL's "canceling statement", the transaction rolls back, and the
 * next unwrapped call on the same anchor succeeds: the injection leaves no pool residue.
 * The depth-32 bound is pinned here as the truncation contract: reported explicitly, and
 * the closure it returns never fabricates duplicates to fill the window.
 *
 * <p>The Team-wide "currently blocked" page runs its own bounded keyset query (no unbounded
 * closure); its budget is held by the page limit and pinned in the M11-A02 scale test.
 */
@SpringBootTest(
        classes = WorkGraphQueryBudgetFaultM11Q01IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class WorkGraphQueryBudgetFaultM11Q01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    @Autowired
    private WorkItemRelationRepository relationRepository;

    @Autowired
    private WorkItemRelationGraphRepository graphRepository;

    @Autowired
    private TransactionExecutor transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private M11D01GraphScaleFixtures.Graph graph;
    private OrganizationId organizationId;
    private TeamId teamId;

    @BeforeEach
    void seedScaleGraph() {
        graph = new M11D01GraphScaleFixtures(jdbcTemplate).seed();
        organizationId = OrganizationId.from(graph.organizationId().toString());
        teamId = TeamId.from(graph.teamId().toString());
    }

    /**
     * The injected timeout cancels the walk as a loud DataAccessException — no partial
     * closure escapes the failed transaction — and the SET LOCAL dies with it: the same
     * anchor, re-queried unwrapped on a recycled connection, walks to the depth bound.
     */
    @Test
    void anInjectedStatementTimeoutFailsLoudlyInsteadOfHalfTrueChains() {
        // Sanity: the same anchor and repository walk to the bound without any injection.
        assertTrue(graphRepository.upstreamClosure(organizationId, graph.chainBottom())
                .truncatedByDepth());

        DataAccessException cancelled =
                assertThrows(
                        DataAccessException.class,
                        () ->
                                transactions.required(
                                        () -> {
                                            // SET LOCAL scopes the timeout to this very
                                            // transaction: the JdbcTemplate below and the
                                            // repository's walk share the same bound connection.
                                            jdbcTemplate.execute(
                                                    "SET LOCAL statement_timeout = '1ms'");
                                            return graphRepository.upstreamChain(
                                                    organizationId, graph.chainBottom());
                                        }),
                        "the 1ms budget must cancel the 2000-deep walk");

        assertTrue(
                carriesCancelingStatement(cancelled),
                "the cause chain must carry PostgreSQL's cancel, got: " + cancelled);

        // No pool residue: the rolled-back transaction took its SET LOCAL with it, and the
        // recycled connection answers the same anchor at full budget.
        BlockedUpstreamClosure after = graphRepository.upstreamClosure(organizationId,
                graph.chainBottom());
        assertTrue(after.truncatedByDepth());
        assertEquals(BlockingChain.MAX_DEPTH, after.upstreamOf().size());
    }

    /**
     * The depth bound is a contract, not a crash: the 2000-deep chain reports
     * truncatedByDepth, the closure stops at exactly MAX_DEPTH nodes, and no node is
     * fabricated twice to fill the window — the ADR-033 guarantee in assertion form.
     */
    @Test
    void depth32TruncationReportsExplicitlyAndNeverFabricatesDuplicates() {
        BlockedUpstreamClosure closure =
                graphRepository.upstreamClosure(organizationId, graph.chainBottom());

        assertTrue(closure.truncatedByDepth(), "the 2000-deep chain must report truncation");
        assertFalse(closure.truncatedByRows(), "the row bound is far away on this shape");
        assertEquals(BlockingChain.MAX_DEPTH, closure.upstreamOf().size());

        List<WorkItemId> allBlockers =
                closure.upstreamOf().values().stream().flatMap(List::stream).toList();
        assertEquals(
                allBlockers.size(),
                allBlockers.stream().distinct().count(),
                "a truncated closure must never duplicate a blocker to fill the window");

        // The anchored adjacency stays honest on the same scale: bounded, not truncated.
        assertFalse(
                relationRepository
                        .findByEndpoint(organizationId, graph.chainBottom())
                        .isEmpty());
    }

    /** Walks the exception cause chain for PostgreSQL's "canceling statement" marker. */
    private static boolean carriesCancelingStatement(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("canceling statement")) {
                return true;
            }
        }
        return false;
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
        WorkPersistenceMapper.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
