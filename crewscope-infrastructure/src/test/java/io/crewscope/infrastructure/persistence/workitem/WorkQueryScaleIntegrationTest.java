package io.crewscope.infrastructure.persistence.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.workitem.WorkItemFilter;
import io.crewscope.application.workitem.WorkItemPage;
import io.crewscope.application.workitem.WorkItemQuery;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkItemSort;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.persistence.responsibility.ResponsibilityAssignmentEntity;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The M9b-A06 scale verification: with a project far past any in-memory window, every ordering
 * still pages to its end without a duplicate or a gap, the default ordering keeps its covering
 * index, and a paged read holds the SQL latency budget — the CI profile seeds two hundred items
 * per project, and {@code -Da06.scale=full} raises the tier to the D05-sized ten-thousand load.
 *
 * <p>The 4vCPU/8GiB end-to-end p95 ≤ 500ms budget belongs to Q01's gate, not to this SQL-level
 * pass; here the claim is only that the query itself stays under 200ms at full scale.
 */
@SpringBootTest(
        classes = WorkQueryScaleIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class WorkQueryScaleIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final int PAGE = 100;
    private static final int WARMUP = 10;
    private static final int SAMPLES = 20;
    private static final long SQL_BUDGET_MILLIS = 200;

    @Autowired
    private WorkItemRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private A06QueryScaleFixtures.Scope scope;
    private PrincipalId viewer;

    @BeforeEach
    void seedScale() {
        A06QueryScaleFixtures.Profile profile = "full".equals(System.getProperty("a06.scale"))
                ? A06QueryScaleFixtures.Profile.FULL
                : A06QueryScaleFixtures.Profile.SMALL;
        scope = new A06QueryScaleFixtures(jdbc).seed(profile);
        viewer = PrincipalId.from(UUID.randomUUID().toString());
    }

    /**
     * Every ordering walks its whole set — equal keys included — with no row twice and none lost,
     * and the deep probe row stays reachable in the tail of each ordering, which is exactly where
     * an unstable tie-breaker or a drifting cursor would drop it.
     */
    @Test
    void everyOrderingWalksTheWholeSetWithoutDuplicatesOrGaps() {
        List<WorkItem> byUpdated = walk(WorkItemSort.UPDATED_AT);
        List<WorkItem> byPriority = walk(WorkItemSort.PRIORITY);
        List<WorkItem> byDue = walk(WorkItemSort.DUE_AT);

        for (List<WorkItem> rows : List.of(byUpdated, byPriority, byDue)) {
            assertEquals(scope.itemCount(), rows.size(), "the walk visits every row once");
            assertEquals(
                    rows.size(),
                    rows.stream().map(item -> item.id().value()).distinct().count(),
                    "no row appears on two pages");
        }
        // One project's full set under three orderings — three views of the same ids.
        Set<UUID> identifiers = Set.copyOf(
                byUpdated.stream().map(item -> item.id().value()).toList());
        for (List<WorkItem> rows : List.of(byPriority, byDue)) {
            assertEquals(identifiers,
                    Set.copyOf(rows.stream().map(item -> item.id().value()).toList()),
                    "every ordering resolves the same authorized set");
        }
        // Each probe sits in the tail of its own ordering: old seconds, the LOW band, the null dates.
        assertTail(byUpdated, "Scale item 00003", WorkItemSort.UPDATED_AT);
        assertTail(byPriority, "Scale item 00003", WorkItemSort.PRIORITY);
        assertTail(byDue, "Scale item 00000", WorkItemSort.DUE_AT);
    }

    /** A plan that stops using the covering index would degrade as the project grows. */
    @Test
    void theDefaultOrderingKeepsItsCoveringIndexAtScale() {
        String plan = String.join("\n", jdbc.queryForList(
                """
                EXPLAIN (COSTS FALSE)
                SELECT id FROM crewscope.work_item
                WHERE organization_id = ? AND team_id = ? AND project_id = ?
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """,
                String.class,
                scope.organizationId(), scope.teamId(), scope.projectId(), PAGE));

        assertTrue(plan.contains("ix_work_item_project_updated"),
                "the default ordering must use ix_work_item_project_updated, plan was:\n" + plan);
    }

    /**
     * After a warm-up the paged read holds the SQL budget at scale — the sampling uses fresh first
     * pages, the shape every list mount opens with.
     */
    @Test
    void pagedReadsHoldTheLatencyBudgetAtScale() {
        for (int index = 0; index < WARMUP; index++) {
            firstPage(WorkItemSort.UPDATED_AT);
        }
        List<Long> samples = new ArrayList<>();
        for (int index = 0; index < SAMPLES; index++) {
            long startedAt = System.nanoTime();
            firstPage(WorkItemSort.UPDATED_AT);
            samples.add((System.nanoTime() - startedAt) / 1_000_000);
        }
        samples.sort(Long::compare);
        long p95 = samples.get((int) Math.ceil(0.95 * SAMPLES) - 1);
        assertTrue(p95 <= SQL_BUDGET_MILLIS,
                "paged first reads must hold p95 <= 200ms, measured p95 was " + p95 + "ms over "
                        + samples);
    }

    private List<WorkItem> walk(WorkItemSort sort) {
        List<WorkItem> rows = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Optional<io.crewscope.application.workitem.WorkItemCursor> cursor = Optional.empty();
        for (int page = 0; page < 1_000; page++) {
            WorkItemPage current = repository.findPage(WorkItemQuery.create(
                    OrganizationId.from(scope.organizationId().toString()),
                    TeamId.from(scope.teamId().toString()),
                    WorkProjectId.from(scope.projectId().toString()),
                    viewer, WorkItemFilter.ALL, sort, cursor, PAGE));
            for (WorkItem row : current.items()) {
                assertTrue(seen.add(row.id().value()), "a row appeared on two pages");
                rows.add(row);
            }
            if (current.nextCursor().isEmpty()) {
                return rows;
            }
            cursor = current.nextCursor();
        }
        throw new AssertionError("the walk never ended: the ordering or the cursor is drifting");
    }

    private WorkItemPage firstPage(WorkItemSort sort) {
        return repository.findPage(WorkItemQuery.create(
                OrganizationId.from(scope.organizationId().toString()),
                TeamId.from(scope.teamId().toString()),
                WorkProjectId.from(scope.projectId().toString()),
                viewer, WorkItemFilter.ALL, sort, Optional.empty(), PAGE));
    }

    private void assertTail(List<WorkItem> rows, String title, WorkItemSort sort) {
        int position = -1;
        for (int index = 0; index < rows.size(); index++) {
            if (title.equals(rows.get(index).title())) {
                position = index;
                break;
            }
        }
        assertTrue(position >= 0, sort + " lost its probe row " + title);
        assertTrue(position >= rows.size() * 7 / 10,
                sort + " probe row " + title + " must sit in the tail, was at " + position
                        + " of " + rows.size());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {WorkItemEntity.class, ResponsibilityAssignmentEntity.class})
    @Import({JpaWorkItemRepositoryAdapter.class, WorkItemEntityMapper.class})
    static class TestApplication {}
}
